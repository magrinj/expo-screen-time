#!/usr/bin/env python3
"""Generate/serve a local signed W-R5 zone. Private signing keys never leave memory."""
import argparse
import hashlib
import json
from pathlib import Path
import time

import dns.dnssec
import dns.flags
import dns.message
import dns.name
import dns.rdatatype
import dns.rcode
import dns.zone
from cryptography.hazmat.primitives.asymmetric import rsa


def validate_zone(zone, now=None):
    keys = zone.find_rrset(zone.origin, 'DNSKEY')
    checked = 0
    for name, node in zone.nodes.items():
        for dataset in node.rdatasets:
            if dataset.rdtype == dns.rdatatype.RRSIG:
                continue
            rrset = zone.find_rrset(name, dataset.rdtype)
            signature = zone.find_rrset(name, 'RRSIG', dataset.rdtype)
            dns.dnssec.validate(rrset, signature, {zone.origin: keys}, now=now)
            checked += 1
    return checked


class SignedZone:
    def __init__(self, zone):
        self.zone = zone
        if not zone.origin.is_subdomain(dns.name.from_text('r3.test.')) or zone.origin == dns.name.from_text('r3.test.'):
            raise ValueError('a dedicated zone below r3.test is required')
        self.checked = validate_zone(zone)

    @classmethod
    def load(cls, path):
        return cls(dns.zone.from_file(str(path), relativize=False))

    def contains(self, name):
        return dns.name.from_text(name).is_subdomain(self.zone.origin)

    def answer(self, packet, transport):
        query = dns.message.from_wire(packet)
        if query.opcode() != 0 or len(query.question) != 1 or query.question[0].rdclass != 1:
            raise ValueError('one ordinary IN question required')
        asked = query.question[0]
        if not asked.name.is_subdomain(self.zone.origin):
            raise ValueError('question outside the signed test zone')
        response = dns.message.make_response(query)
        response.flags |= dns.flags.AA
        # This fixture serves existing positive RRsets, not a recursive resolver or denial oracle.
        try:
            response.answer.append(self.zone.find_rrset(asked.name, asked.rdtype))
            if query.ednsflags & dns.flags.DO:
                response.answer.append(self.zone.find_rrset(asked.name, 'RRSIG', asked.rdtype))
        except KeyError:
            response.answer.clear()
            response.flags &= ~dns.flags.AA
            response.set_rcode(dns.rcode.REFUSED)
        maximum = 65535 if transport == 'tcp' else max(512, min(query.payload or 512, 1232))
        return response.to_wire(max_size=maximum, prefer_truncation=(transport == 'udp'))


def generate(name):
    origin = dns.name.from_text(name)
    if not origin.is_subdomain(dns.name.from_text('r3.test.')) or origin == dns.name.from_text('r3.test.'):
        raise ValueError('a dedicated zone below r3.test is required')
    now = int(time.time())
    text = f'''$ORIGIN {origin}
$TTL 3600
@ IN SOA ns hostmaster {now} 60 60 86400 60
@ IN NS ns
ns IN A 10.0.2.2
large IN TXT {' '.join('"' + ('x' * 200) + '"' for _ in range(12))}
'''
    zone = dns.zone.from_text(text, origin=origin, relativize=False)
    # Two real 4096-bit public keys plus the KSK signature make DNSKEY > 1460 bytes.
    keys = []
    for flags in (257, 256):
        private = rsa.generate_private_key(public_exponent=65537, key_size=4096)
        keys.append((private, dns.dnssec.make_dnskey(private.public_key(), 8, flags=flags)))
    dns.dnssec.sign_zone(zone, keys=keys, inception=now - 60, expiration=now + 30 * 86400)
    fixture = SignedZone(zone)
    query = dns.message.make_query(origin, 'DNSKEY', want_dnssec=True).to_wire()
    wire = fixture.answer(query, 'tcp')
    if len(wire) <= 1460:
        raise ValueError('signed DNSKEY response must exceed 1460 bytes')
    metadata = dict(zone=str(origin), generatedAt=now, expiresAt=now + 30 * 86400,
                    validatedRrsets=fixture.checked, dnskeyResponseBytes=len(wire),
                    trust='local self-signed test zone; no public delegation', privateKeysSaved=False)
    return fixture, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--zone', default='signed.r3.test.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    fixture, metadata = generate(args.zone)
    zone_path = args.output / 'signed.zone'
    fixture.zone.to_file(str(zone_path), relativize=False, want_origin=True)
    # Validate the serialized fixture too, so later runs can use exactly these bytes.
    SignedZone.load(zone_path)
    metadata['zoneSha256'] = hashlib.sha256(zone_path.read_bytes()).hexdigest()
    (args.output / 'zone.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
