#!/usr/bin/env python3
"""Run the real Android View/AFD upload fixture without AGP, APKs or device operations."""
import argparse
import hashlib
import json
import locale
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ["WebTransferPolicyTest", "WebUploadSessionBudgetTest", "WebUploadSourceRetryTest",
           "WebUploadStreamTest", "WebUploadDiskBudgetTest", "PictureInPicturePolicyTest"]


def run(command, log, environment):
    result = subprocess.run(command, cwd=ROOT, env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    raw = result.stdout
    log.with_suffix(log.suffix + ".raw").write_bytes(raw)
    native_encoding = (locale.getencoding() if hasattr(locale, "getencoding")
                       else locale.getpreferredencoding(False))
    decoded = None
    encoding = "utf-8"
    for candidate in dict.fromkeys(["utf-8", native_encoding,
                                   "cp936" if os.name == "nt" else "utf-8"]):
        try:
            decoded = raw.decode(candidate)
            encoding = candidate
            break
        except (UnicodeDecodeError, LookupError):
            continue
    if decoded is None:
        decoded = raw.decode("utf-8", errors="backslashreplace")
        encoding = "utf-8/backslashreplace"
    log.write_text(decoded, encoding="utf-8")
    log.with_suffix(log.suffix + ".result.json").write_text(json.dumps({
        "command": command, "returncode": result.returncode,
        "rawBytes": len(raw), "displayEncoding": encoding,
        "rawLog": str(log.with_suffix(log.suffix + ".raw")),
    }, indent=2), encoding="utf-8")
    terminal_encoding = sys.stdout.encoding or "utf-8"
    printable = decoded.encode(terminal_encoding, errors="backslashreplace").decode(terminal_encoding)
    print(printable, end="")
    result.check_returncode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--gradle-home", type=Path,
                        default=Path.home() / ".gradle")
    args = parser.parse_args()
    home = args.gradle_home.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    java = str(args.jdk / "bin" / ("java" + suffix)) if args.jdk else shutil.which("java")
    javac = str(args.jdk / "bin" / ("javac" + suffix)) if args.jdk else shutil.which("javac")
    if not java or not javac:
        raise RuntimeError("UPLOAD_FRAMEWORK_JDK_REQUIRED")
    roots = [home, Path.home() / ".gradle"]
    def jar(pattern):
        for directory in roots:
            found = sorted(directory.glob(pattern))
            if found:
                return found[0]
        raise RuntimeError("UPLOAD_FRAMEWORK_JUNIT_CACHE_REQUIRED: " + pattern)
    junit = jar("caches/modules-2/files-2.1/junit/junit/4.13.2/**/junit-4.13.2.jar")
    hamcrest = jar("caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/**/hamcrest-core-1.3.jar")
    backup_dependencies = json.loads((ROOT / "tools/backup-dependencies.lock.json").read_text(encoding="utf-8"))
    gson_lock = next(item for item in backup_dependencies["dependencies"]
                     if item["group"] == "com.google.code.gson" and item["name"] == "gson")
    gson = jar("caches/modules-2/files-2.1/com.google.code.gson/gson/" + gson_lock["version"] +
               "/**/gson-" + gson_lock["version"] + ".jar")
    if hashlib.sha256(gson.read_bytes()).hexdigest() != gson_lock["sha256"]:
        raise RuntimeError("UPLOAD_FRAMEWORK_GSON_CACHE_CHANGED")
    gradle = os.environ.get("DSHA_GRADLE")
    if not gradle:
        name = "gradle.bat" if os.name == "nt" else "gradle"
        for directory in roots:
            found = sorted(directory.glob("wrapper/dists/gradle-9.3.1-bin/**/bin/" + name))
            if found:
                gradle = str(found[0])
                break
    if not gradle:
        raise RuntimeError("UPLOAD_FRAMEWORK_GRADLE_9_3_1_REQUIRED")
    output = ROOT / "app/build/host-tests/web-upload-framework/unit"
    output.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    if args.jdk:
        environment["JAVA_HOME"] = str(args.jdk.resolve())
    cp = os.pathsep.join(map(str, [junit, hamcrest, gson]))
    generated = output / "generated"
    run([sys.executable, "-B", str(ROOT / "tools/prepare-ui-languages.py"),
         "--output", str(generated)], output / "language.log", environment)
    sourcepath = os.pathsep.join(map(str, [ROOT / "app/src/main/java",
                                         generated]))
    sources = [str(ROOT / "app/src/test/java/com/deepseekharness/app/util" / (name + ".java"))
               for name in CLASSES]
    # DocumentStreams uses the production error classifier; javac resolves its pure-Java closure.
    sources.append(str(ROOT / "app/src/main/java/com/deepseekharness/app/backup/BackupErrorCode.java"))
    run([javac, "-encoding", "UTF-8", "--release", "17", "-d", str(output),
         "-cp", cp, "-sourcepath", sourcepath] + sources, output / "compile.log", environment)
    run([java, "-cp", os.pathsep.join([str(output), cp]), "org.junit.runner.JUnitCore"] +
        ["com.deepseekharness.app.util." + name for name in CLASSES],
        output / "junit.log", environment)
    # The historical fixture classpath must consume this run's compiled classes.
    # Override only its old file dependency, without rewriting the fixture or old receipts.
    current_classes = str(output.resolve()).replace("\\", "/").replace("'", "\\'")
    historical_classes = str((ROOT / "artifacts/pr171/uploads/unit").resolve()).replace("\\", "/").replace("'", "\\'")
    gson_dependency = str(gson.resolve()).replace("\\", "/").replace("'", "\\'")
    init_script = output.parent / "current-classes.gradle"
    init_script.write_text("""allprojects {
    afterEvaluate {
        def historical = file('%s').canonicalFile
        configurations.named('testImplementation').configure {
            dependencies.removeAll { dependency ->
                dependency instanceof org.gradle.api.artifacts.FileCollectionDependency &&
                    dependency.files.files.any { it.canonicalFile == historical }
            }
        }
        dependencies.add('testImplementation', files('%s', '%s'))
    }
}
""" % (historical_classes, current_classes, gson_dependency), encoding="utf-8")
    run([gradle, "-p", str(ROOT / "tools/fixtures/uploads-framework"),
         "--init-script", str(init_script), "--gradle-user-home", str(home),
         "test", "--rerun-tasks", "--no-daemon", "--console=plain"],
        output.parent / "framework.log", environment)
    print("PASS_UPLOAD_FRAMEWORK_HOST: real View/AFD behavior; cache syscall model; no device/APK")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        print("UPLOAD_FRAMEWORK_COMMAND_FAILED: returncode=" + str(failure.returncode), file=sys.stderr)
        sys.exit(failure.returncode if failure.returncode >= 0 else 128 - failure.returncode)
