#!/usr/bin/env python3
"""Build pinned upstream tcpdump/libpcap with the installed arm64 Android NDK.

Artifacts stay local. Requires make, a C toolchain, flex and bison on the host.
The output uses 16-KiB ELF alignment for the Android 17 emulator.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.request

SOURCES = {
    'libpcap-1.10.5': '37ced90a19a302a7f32e458224a00c365c117905c2cd35ac544b6880a81488f0',
    'tcpdump-4.99.5': '8c75856e00addeeadf70dad67c9ff3dd368536b2b8563abf6854d7c764cd3adb',
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ndk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix='capture-', dir=args.output.resolve()))
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    env = dict(os.environ, CC=str(toolchain / 'aarch64-linux-android24-clang'),
               AR=str(toolchain / 'llvm-ar'), RANLIB=str(toolchain / 'llvm-ranlib'),
               LDFLAGS='-Wl,-z,max-page-size=16384')
    with (build / 'build.log').open('w') as log:
        for name, expected in SOURCES.items():
            archive = build / (name + '.tar.gz')
            urllib.request.urlretrieve('https://www.tcpdump.org/release/' + archive.name, archive)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
                raise RuntimeError('Source checksum mismatch: ' + name)
            with tarfile.open(archive) as source:
                source.extractall(build, filter='data')
            options = (['--disable-shared', '--with-pcap=linux', '--disable-dbus', '--without-libnl',
                        '--disable-bluetooth', '--disable-usb'] if name.startswith('libpcap') else
                       ['--without-crypto', '--disable-smb'])
            for command in [['./configure', '--host=aarch64-linux-android', *options], ['make', '-j2']]:
                subprocess.run(command, cwd=build / name, env=env, check=True, stdout=log, stderr=subprocess.STDOUT)
    binary = build / 'tcpdump-4.99.5/tcpdump'
    metadata = dict(binary=str(binary), sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                    sourceSha256=SOURCES, ndk=str(args.ndk.resolve()), linkerFlags=env['LDFLAGS'])
    (build / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
