"""Conservative, bounded DNS-over-TCP reconstruction for a single capture interface.

RFC 9293 sequence space (including SYN/FIN) and RFC 1035 length framing. This
reports observations, not the remote TCP implementation's acceptance of them.
"""
from array import array
import struct

from tcp_capture import message, require

MODULO = 1 << 32
MAX_STREAM = 2 * 1024 * 1024


def relative(value, base):
    return (value - base + (1 << 31)) % MODULO - (1 << 31)


def direction(rows):
    syn = rows[0]
    require(syn['flags'] & 2, 'capture starts after SYN')
    base = (syn['sequence'] + 1) % MODULO
    data, first = bytearray(), array('I')
    segments, repeats, fins, resets = [], [], [], []
    fin_offset, observed_end = None, 0
    for row in rows:
        flags, payload = row['flags'], row['payload']
        require(not flags & 32, 'TCP urgent data unsupported')
        if flags & 2:
            require(row['sequence'] == syn['sequence'] and not payload and not flags & 5,
                    'conflicting SYN or TCP Fast Open unsupported')
            continue
        start = relative(row['sequence'], base)
        if payload or flags & 1:
            require(0 <= start <= MAX_STREAM, 'TCP sequence outside bounded stream')
        else:
            require(-1 <= start <= MAX_STREAM, 'TCP control sequence outside bounded stream')
        end = start + len(payload)
        observed_end = max(observed_end, end - int(fin_offset is not None and start == fin_offset + 1))
        if payload:
            require(flags & 16 and not flags & 4, 'payload without ACK or with RST unsupported')
            require(end <= MAX_STREAM, 'stream exceeds 2 MiB bound')
            require(fin_offset is None or end <= fin_offset, 'payload extends beyond FIN')
            extra = end - len(data)
            if extra > 0:
                data.extend(b'\x00' * extra)
                first.extend(array('I', [0]) * extra)
            repeated = 0
            for index, value in enumerate(payload, start):
                if first[index]:
                    require(data[index] == value, 'contradictory TCP overlap')
                    repeated += 1
                else:
                    data[index], first[index] = value, row['packet']
            segment = dict(packet=row['packet'], atUs=row['atUs'], start=start, end=end,
                           bytes=len(payload), repeatedBytes=repeated,
                           captureDirection=row['direction'], ipId=row.get('ipId'))
            segments.append(segment)
            if repeated:
                repeats.append(dict(segment))
        if flags & 1:
            require(fin_offset is None or end == fin_offset, 'conflicting FIN positions')
            fin_offset = end
            require(len(data) <= end, 'FIN precedes observed payload')
            fins.append(row['packet'])
        if flags & 4:
            resets.append(row['packet'])
    require(0 not in first and len(data) >= observed_end, 'missing TCP byte range')
    require(fin_offset is None or fin_offset == len(data), 'missing payload before FIN')
    frames, offset, ready_packet = [], 0, 0
    while offset < len(data):
        require(offset + 2 <= len(data), 'incomplete DNS length prefix')
        size = struct.unpack_from('!H', data, offset)[0]
        end = offset + 2 + size
        require(size >= 12 and end <= len(data), 'incomplete or invalid DNS frame')
        require(len(frames) < 512, 'more than 512 DNS frames in one direction')
        decoded = message(bytes(data[offset + 2:end]))
        first_seen = first[offset:end]
        # Later frames cannot be consumed until every earlier TCP byte arrives.
        ready_packet = max(ready_packet, max(first_seen))
        prefix_packets = set(first[offset:offset + 2])
        body_packets = set(first[offset + 2:end])
        decoded.update(index=len(frames), start=offset, end=end,
                       firstPacket=min(first_seen), completePacket=ready_packet,
                       bytesObservedPacket=max(first_seen),
                       firstTransmissionPackets=sorted(set(first_seen)),
                       prefixBodySeparateSegments=not bool(prefix_packets & body_packets),
                       prefixSplitAcrossSegments=len(prefix_packets) > 1)
        frames.append(decoded)
        offset = end
    for index in range(1, len(first)):
        first[index] = max(first[index], first[index - 1])
    return dict(base=base, payloadBytes=len(data), frames=frames, segments=segments, _byteReady=first,
                repeatedPayload=repeats, finPackets=fins, resetPackets=resets,
                finOffset=fin_offset)


def decode_flow(rows):
    directions = [[row for row in rows if (row['sport'] == 53) == server] for server in (False, True)]
    require(all(directions), 'missing peer direction/handshake')
    client_syn, server_syn = (part[0] for part in directions)
    require(client_syn['flags'] & 0x17 == 2 and server_syn['flags'] & 0x17 == 18,
            'expected SYN then SYN/ACK')
    require(client_syn['packet'] < server_syn['packet'], 'SYN/ACK precedes SYN')
    require(server_syn['ack'] == (client_syn['sequence'] + 1) % MODULO, 'SYN/ACK acknowledges wrong SYN')
    handshake = next((row for row in directions[0] if row['packet'] > server_syn['packet'] and
                      row['flags'] & 0x17 == 16 and row['ack'] == (server_syn['sequence'] + 1) % MODULO), None)
    require(handshake is not None, 'missing final handshake ACK')
    for row in rows:
        if row['payload']:
            require(row['packet'] >= handshake['packet'], 'payload before handshake')
        if row['flags'] & 2:
            require(row['packet'] < handshake['packet'], 'SYN after established connection is ambiguous')
            if row['sport'] == 53:
                require(row['flags'] & 0x17 == 18 and row['ack'] == (client_syn['sequence'] + 1) % MODULO,
                        'conflicting SYN/ACK retry')
            else:
                require(row['flags'] & 0x17 == 2, 'conflicting client SYN retry')
    streams = [direction(part) for part in directions]
    by_packet = {row['packet']: row for row in rows}
    for index, stream in enumerate(streams):
        acknowledgments = []
        for row in directions[1 - index]:
            if row['flags'] & 16:
                ack = relative(row['ack'], stream['base'])
                limit = stream['payloadBytes'] + int(stream['finOffset'] is not None)
                require(0 <= ack <= limit, 'ACK covers uncaptured bytes or FIN')
                byte_ack = min(ack, stream['payloadBytes'])
                require(not byte_ack or stream['_byteReady'][byte_ack - 1] < row['packet'],
                        'ACK precedes captured bytes; later repeats cannot repair missing evidence')
                require(ack <= stream['payloadBytes'] or row['packet'] > stream['finPackets'][0],
                        'ACK precedes captured FIN')
                acknowledgments.append((row['packet'], ack))
        stream.pop('_byteReady')
        stream['finAcknowledged'] = stream['finOffset'] is not None and any(
            packet > stream['finPackets'][0] and ack == stream['finOffset'] + 1
            for packet, ack in acknowledgments)
        for frame in stream['frames']:
            complete = frame['completePacket']
            frame['completedAtUs'] = by_packet[complete]['atUs']
            frame['ackPacket'] = next((packet for packet, ack in acknowledgments
                                      if packet > complete and ack >= frame['end']), None)
        for repeat in stream['repeatedPayload']:
            before = max((ack for packet, ack in acknowledgments if packet < repeat['packet']), default=0)
            repeat['ackedThroughBefore'] = before
            repeat['ackCoversSegmentAfterPacket'] = next((packet for packet, ack in acknowledgments
                                                         if packet > repeat['packet'] and ack >= repeat['end']), None)
    queries, replies = (stream['frames'] for stream in streams)
    require(all(not row['response'] for row in queries) and all(row['response'] for row in replies),
            'DNS message travels in wrong direction')
    outstanding, transactions = {}, []
    events = sorted([(row['completePacket'], side, row['index'], row)
                     for side, frames in enumerate((queries, replies)) for row in frames])
    for _, side, _, frame in events:
        key = (frame['id'], frame['name'], frame['qtype'], frame['qclass'])
        if side == 0:
            require(key not in outstanding, 'ambiguous outstanding DNS identity')
            outstanding[key] = frame
        else:
            require(key in outstanding, 'unmatched or premature DNS response')
            query = outstanding.pop(key)
            client_fin = streams[0]['finPackets']
            server_fin = streams[1]['finPackets']
            transactions.append(dict(query=query['index'], reply=frame['index'], name=frame['name'],
                                     id=frame['id'], qtype=frame['qtype'],
                                     clientFinBeforeReply=bool(client_fin and
                                                              query['completePacket'] <= client_fin[0] < frame['firstPacket']),
                                     replyBeforeServerFin=bool(server_fin and frame['completePacket'] <= server_fin[0]),
                                     responseAcknowledged=frame['ackPacket'] is not None))
    first_reply = min((row['firstPacket'] for row in replies), default=None)
    pipelined = [row['index'] for row in queries if first_reply and row['completePacket'] < first_reply]
    return dict(handshakePacket=handshake['packet'], client=streams[0], server=streams[1],
                transactions=transactions, unansweredQueries=[row['index'] for row in outstanding.values()],
                queryOrder=[row['id'] for row in queries], replyOrder=[row['id'] for row in replies],
                queriesBeforeFirstReply=pipelined,
                closureAcknowledged=all(stream['finAcknowledged'] for stream in streams),
                resetObserved=any(stream['resetPackets'] for stream in streams),
                allQueriesAnswered=bool(queries) and not outstanding,
                retransmissionVerified=False)


def connections(rows):
    """Separate each observed SYN generation; port reuse never joins two streams."""
    active, result = {}, []
    for row in rows:
        server = row['sport'] == 53
        key = ((row['dst'], row['dport'], row['src']) if server else
               (row['src'], row['sport'], row['dst']))
        initial = row['flags'] & 0x17 == 2
        flow = active.get(key)
        if flow is None or initial and flow['terminal']:
            require(len(result) < 256, 'more than 256 connections')
            flow = dict(connection=len(result) + 1, clientAddress=key[0], clientPort=key[1],
                        serverAddress=key[2], serverPort=53, rows=[], terminal=False, fins=set())
            active[key] = flow
            result.append(flow)
        # Duplicate SYN before handshake is retained. A new SYN during an active
        # generation is ambiguous and fails in decode_flow instead of reconnecting.
        if flow['terminal'] and (row['payload'] or row['flags'] & 2):
            flow.setdefault('error', 'payload/SYN after reset or both FINs; capture ambiguous')
        flow['rows'].append(row)
        if row['flags'] & 1:
            flow['fins'].add(server)
        if row['flags'] & 4 or len(flow['fins']) == 2:
            flow['terminal'] = True
    decoded = []
    for flow in result:
        summary = {key: value for key, value in flow.items() if key not in ('rows', 'terminal', 'fins', 'error')}
        summary.update(firstPacket=flow['rows'][0]['packet'], lastPacket=flow['rows'][-1]['packet'],
                       resetPackets=[row['packet'] for row in flow['rows'] if row['flags'] & 4])
        try:
            require('error' not in flow, flow.get('error', ''))
            summary.update(decode_flow(flow['rows']))
            summary['errors'] = []
        except ValueError as error:
            summary['errors'] = [str(error)]
        decoded.append(summary)
    return decoded
