"""Bounded SLL2/PCAP and DNS framing inputs for the offline W-R5 reader.

No checksum verdict: captures can precede checksum offload. DNS RDATA stays raw;
cryptographic validation belongs to tcp_dns_zone, not this framing reader.
"""
import base64
import ipaddress
from pathlib import Path
import struct

MAX_CAPTURE = 64 * 1024 * 1024
MAX_PACKET = 1024 * 1024


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def packets(path):
    path = Path(path)
    require(path.stat().st_size <= MAX_CAPTURE, 'capture exceeds 64 MiB reader bound')
    with path.open('rb') as source:
        header = source.read(24)
        require(len(header) == 24, 'missing PCAP header')
        endian = {b'\xd4\xc3\xb2\xa1': '<', b'\xa1\xb2\xc3\xd4': '>'}.get(header[:4])
        require(endian is not None, 'expected microsecond PCAP')
        major, minor, _, _, snaplen, link = struct.unpack(endian + 'HHIIII', header[4:])
        require((major, minor, link) == (2, 4, 276), 'expected PCAP 2.4 / Linux SLL2')
        count, previous = 0, -1
        while record := source.read(16):
            require(len(record) == 16, 'truncated PCAP record')
            sec, micro, captured, original = struct.unpack(endian + 'IIII', record)
            require(20 <= captured <= min(snaplen, MAX_PACKET) and captured == original,
                    'truncated or oversized captured packet')
            raw = source.read(captured)
            require(len(raw) == captured, 'truncated packet bytes')
            stamp = sec * 1000000 + micro
            require(micro < 1000000 and stamp >= previous, 'invalid or decreasing capture timestamp')
            previous = stamp
            count += 1
            require(count <= 200000, 'capture exceeds 200000 packet reader bound')
            protocol, reserved, interface, hardware, direction, addrlen = struct.unpack_from('!HHIHBB', raw)
            require(reserved == 0 and addrlen <= 8 and direction <= 4, 'unsupported SLL2 header')
            yield dict(packet=count, atUs=stamp, interface=interface, direction=direction,
                       networkProtocol=protocol, network=raw[20:])


def transport(row):
    """Decode only the chosen interface; never merge observations across interfaces."""
    data, protocol = row['network'], row['networkProtocol']
    fields = {key: value for key, value in row.items() if key != 'network'}
    if protocol == 0x0800:
        require(len(data) >= 20 and data[0] >> 4 == 4, 'truncated or invalid IPv4 header')
        start, length = (data[0] & 15) * 4, struct.unpack_from('!H', data, 2)[0]
        require(20 <= start <= length == len(data), 'invalid IPv4 length')
        require(not struct.unpack_from('!H', data, 6)[0] & 0xbfff, 'IPv4 fragments/reserved flag unsupported')
        kind = data[9]
        src, dst = (str(ipaddress.ip_address(data[i:i + 4])) for i in (12, 16))
        fields['ipId'] = struct.unpack_from('!H', data, 4)[0]
    elif protocol == 0x86dd:
        require(len(data) >= 40 and data[0] >> 4 == 6, 'truncated or invalid IPv6 header')
        require(40 + struct.unpack_from('!H', data, 4)[0] == len(data), 'invalid IPv6 length/jumbogram')
        src, dst = (str(ipaddress.ip_address(data[i:i + 16])) for i in (8, 24))
        kind, start, extensions = data[6], 40, 0
        while kind in (0, 43, 60):
            require(start + 2 <= len(data) and extensions < 8, 'invalid IPv6 extension chain')
            next_kind, size = data[start], (data[start + 1] + 1) * 8
            require(start + size <= len(data), 'truncated IPv6 extension')
            start, kind, extensions = start + size, next_kind, extensions + 1
        require(kind not in (44, 50, 51), 'IPv6 fragment/ESP/AH unsupported')
    else:
        return None
    if kind not in (6, 17):
        return None
    data = data[start:]
    require(len(data) >= (20 if kind == 6 else 8), 'truncated transport header')
    sport, dport = struct.unpack_from('!HH', data)
    if 53 not in (sport, dport):
        return None
    require(sport != dport, 'ambiguous port-53 endpoints')
    fields.update(src=src, dst=dst, sport=sport, dport=dport, protocol=kind)
    if kind == 17:
        require(struct.unpack_from('!H', data, 4)[0] == len(data), 'invalid UDP length')
        fields['payload'] = data[8:]
    else:
        start = (data[12] >> 4) * 4
        require(20 <= start <= len(data), 'invalid TCP data offset')
        require(not data[12] & 15, 'TCP reserved/extended flags unsupported')
        sequence, ack = struct.unpack_from('!II', data, 4)
        fields.update(sequence=sequence, ack=ack, flags=data[13], payload=data[start:])
    return fields


def name(data, start):
    """Controlled ASCII names, with bounded backward compression and wire length."""
    labels, seen, offset, end, expanded = [], set(), start, None, 1
    while True:
        require(offset < len(data) and offset not in seen, 'invalid DNS name/pointer')
        seen.add(offset)
        size = data[offset]
        if size == 0:
            return '.'.join(labels), end if end is not None else offset + 1
        if size & 0xc0 == 0xc0:
            require(offset + 1 < len(data), 'truncated DNS pointer')
            target = ((size & 0x3f) << 8) | data[offset + 1]
            require(12 <= target < offset, 'DNS pointer must point backward into message')
            if end is None:
                end = offset + 2
            offset = target
            continue
        require(size <= 63 and offset + 1 + size <= len(data), 'invalid DNS label')
        label = data[offset + 1:offset + 1 + size]
        require(all(33 <= value <= 126 and value not in (46, 92) for value in label),
                'expected unambiguous controlled ASCII DNS labels')
        expanded += size + 1
        require(expanded <= 255, 'DNS name exceeds 255 bytes')
        labels.append(label.decode('ascii').lower())
        offset += size + 1


def message(data):
    require(12 <= len(data) <= 65535, 'invalid DNS message length')
    ident, flags, qd, an, ns, ar = struct.unpack_from('!6H', data)
    require(qd == 1 and not flags & 0x7840, 'expected one standard DNS question')
    question, offset = name(data, 12)
    require(offset + 4 <= len(data), 'truncated DNS question')
    qtype, qclass = struct.unpack_from('!HH', data, offset)
    offset += 4
    records = []
    for section, count in (('answer', an), ('authority', ns), ('additional', ar)):
        for _ in range(count):
            owner, offset = name(data, offset)
            require(offset + 10 <= len(data), 'truncated DNS RR header')
            kind, cls, ttl, length = struct.unpack_from('!HHIH', data, offset)
            offset += 10
            require(offset + length <= len(data), 'truncated DNS RDATA')
            records.append(dict(section=section, owner=owner, type=kind, cls=cls, ttl=ttl, bytes=length))
            offset += length
    require(offset == len(data), 'unparsed DNS message bytes')
    return dict(id=ident, response=bool(flags & 0x8000), truncated=bool(flags & 0x200),
                rcode=flags & 15, flags=flags, name=question, qtype=qtype, qclass=qclass,
                records=records, bytes=len(data), wireBase64=base64.b64encode(data).decode())
