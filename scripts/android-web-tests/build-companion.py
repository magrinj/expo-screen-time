#!/usr/bin/env python3
"""Build an emulator-only VPN companion or DNS client without Gradle dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    parser.add_argument("--build-tools", default="37.0.0")
    parser.add_argument("--platform", default="android-37.0")
    parser.add_argument("--fixture", choices=("companion", "dns-client"), default="companion")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent / args.fixture
    args.output.mkdir(parents=True, exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix=args.fixture + "-", dir=args.output.resolve()))
    classes = build / "classes"
    classes.mkdir()
    sdk = args.sdk.resolve()
    android = sdk / "platforms" / args.platform / "android.jar"
    sdk_tools = sdk / "build-tools" / args.build_tools
    sources = sorted((source / "src").glob("*.java"))
    run("javac", "--release", "8", "-classpath", android, "-d", classes, *sources)
    run("jar", "cf", build / "classes.jar", "-C", classes, ".")
    run(sdk_tools / "d8", "--lib", android, "--min-api", "24", "--output", build, build / "classes.jar")
    run(sdk_tools / "aapt2", "link", "-I", android, "--manifest", source / "AndroidManifest.xml", "-o", build / "unsigned.apk")
    with zipfile.ZipFile(build / "unsigned.apk", "a") as apk:
        apk.write(build / "classes.dex", "classes.dex")
    run(sdk_tools / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
    # Disposable test key only. Never use this harness to sign a distributable app.
    key = args.output.resolve() / "companion-test.keystore"
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "test", "-keyalg", "RSA", "-validity", "3650", "-dname", "CN=W1c Emulator Test")
    apk = build / (args.fixture + ".apk")
    run(sdk_tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "test", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", apk, build / "aligned.apk")
    run(sdk_tools / "apksigner", "verify", apk)
    metadata = {"apk": str(apk), "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "sources": {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in [source / "AndroidManifest.xml", *sources]}}
    (build / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
