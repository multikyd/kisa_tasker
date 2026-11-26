"""Build and verify the signed APK and the matching GitHub update manifest."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
UPDATE_URL = "https://raw.githubusercontent.com/multikyd/kisa_tasker/main/kisa_tasker.apk"
DEPENDENCIES = {
    "aidl": "33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963",
    "api": "4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b",
    "provider": "b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3",
    "shared": "4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635",
}


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME")
                        or str(Path.home() / "AppData/Local/Android/Sdk"))
    parser.add_argument("--jdk", default=os.getenv("JAVA_HOME") or r"C:\Program Files\Java\jdk-24")
    parser.add_argument("--signing", type=Path, default=ROOT / ".signing")
    parser.add_argument("--notes", required=True, help="Korean update notes")
    parser.add_argument("--initialize-signing", action="store_true",
                        help="Generate the signing key for the first release only")
    args = parser.parse_args()
    sdk = Path(args.sdk)
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-36/android.jar"
    java = Path(args.jdk) / "bin"
    manifest = ROOT / "AndroidManifest.xml"
    package = ET.parse(manifest).getroot()
    code = int(package.attrib[ANDROID_NS + "versionCode"])
    version_name = package.attrib[ANDROID_NS + "versionName"]
    signing = args.signing.resolve()
    keystore = signing / "kisa-tasker.p12"
    password = signing / "password.txt"
    signing_env = os.environ.copy()

    if args.initialize_signing:
        if keystore.exists() or password.exists() or (ROOT / "kisa_tasker.apk").exists():
            raise SystemExit("A key or an earlier release already exists. Reuse the existing key.")
        signing.mkdir(parents=True, exist_ok=True)
        signing_env["KISA_TASKER_SIGNING_PASSWORD"] = secrets.token_urlsafe(36)
        with password.open("x", encoding="utf-8") as stream:
            stream.write(signing_env["KISA_TASKER_SIGNING_PASSWORD"] + "\n")
        run(java / "keytool.exe", "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
            "-storepass:env", "KISA_TASKER_SIGNING_PASSWORD", "-alias", "kisa-tasker",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Kisa Tasker", env=signing_env)
    if not keystore.is_file() or not password.is_file():
        raise SystemExit("Missing release signing key. Supply --signing with the original key directory.")
    signing_env["KISA_TASKER_SIGNING_PASSWORD"] = password.read_text(encoding="utf-8").strip()

    metadata = ROOT / "kisa_tasker_latest.json"
    previous_apk = ROOT / "kisa_tasker.apk"
    if metadata.is_file():
        previous = json.loads(metadata.read_text(encoding="utf-8"))
        if code <= previous["versionCode"]:
            raise SystemExit("Increase AndroidManifest.xml versionCode before releasing again.")
        if not previous_apk.is_file() or hashlib.sha256(previous_apk.read_bytes()).hexdigest() != previous["sha256"]:
            raise SystemExit("The previous release APK must match its metadata.")

    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="release-", dir=build) as scratch:
        work = Path(scratch)
        jars = []
        for name, expected in DEPENDENCIES.items():
            library = ROOT / "vendor" / f"{name}-13.1.5.aar"
            if hashlib.sha256(library.read_bytes()).hexdigest() != expected:
                raise SystemExit(f"Unexpected Shizuku library: {library.name}")
            jar = work / f"{name}.jar"
            with zipfile.ZipFile(library) as source:
                jar.write_bytes(source.read("classes.jar"))
            jars.append(jar)

        compiled = work / "res.zip"
        base_apk = work / "base.apk"
        generated = work / "generated"
        classes = work / "classes"
        generated.mkdir()
        classes.mkdir()
        shutil.copytree(ROOT / "res", work / "res")
        shutil.copyfile(manifest, work / "AndroidManifest.xml")
        run(tools / "aapt2.exe", "compile", "--dir", "res", "-o", compiled.name, cwd=work)
        run(tools / "aapt2.exe", "link", "-I", android, "--manifest", "AndroidManifest.xml",
            "--java", generated.name, "-o", base_apk.name, compiled.name, cwd=work)
        sources = [*ROOT.joinpath("src").rglob("*.java"), *generated.rglob("*.java")]
        run(java / "javac.exe", "--release", "11", "-encoding", "UTF-8", "-classpath",
            os.pathsep.join(map(str, [android, *jars])), "-d", classes, *sources)
        local_jar = work / "app.jar"
        with zipfile.ZipFile(local_jar, "w") as archive:
            for path in classes.rglob("*.class"):
                archive.write(path, path.relative_to(classes).as_posix())
        dex = work / "dex.zip"
        run(java / "java.exe", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8",
            "--min-api", "30", "--lib", android, "--output", dex, local_jar, *jars)
        with zipfile.ZipFile(base_apk, "a") as apk, zipfile.ZipFile(dex) as android_code:
            for item in android_code.namelist():
                if item.endswith(".dex"):
                    apk.writestr(item, android_code.read(item))
            apk.write(ROOT / "vendor/LICENSE-Shizuku", "assets/licenses/Shizuku.txt")
        aligned = work / "aligned.apk"
        signed = work / "signed.apk"
        run(tools / "zipalign.exe", "-f", "4", base_apk.name, aligned.name, cwd=work)
        signer = (java / "java.exe", "-jar", tools / "lib/apksigner.jar")
        run(*signer, "sign", "--ks", keystore, "--ks-key-alias", "kisa-tasker", "--ks-pass",
            "env:KISA_TASKER_SIGNING_PASSWORD", "--v4-signing-enabled", "false",
            "--out", signed, aligned, env=signing_env)
        run(tools / "zipalign.exe", "-c", "4", signed.name, cwd=work)
        run(*signer, "verify", "--verbose", "--print-certs", signed)
        if previous_apk.is_file():
            def signer_hash(apk):
                output = subprocess.check_output(
                    [*map(str, signer), "verify", "--print-certs", str(apk)], text=True)
                return [line for line in output.splitlines() if "certificate SHA-256 digest:" in line]
            if signer_hash(previous_apk) != signer_hash(signed):
                raise SystemExit("Release signing certificate changed.")
        shutil.copyfile(signed, previous_apk)

    metadata.write_text(json.dumps({
        "versionCode": code, "versionName": version_name, "apkUrl": UPDATE_URL,
        "sha256": hashlib.sha256(previous_apk.read_bytes()).hexdigest(),
        "releaseNotes": {"ko": args.notes},
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Built and verified {previous_apk.name} {version_name} ({code})")


if __name__ == "__main__":
    main()
