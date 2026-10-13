#!/usr/bin/env python3
"""Run the real Android View/AFD upload fixture without AGP, APKs or device operations."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ["WebTransferPolicyTest", "WebUploadSessionBudgetTest", "WebUploadSourceRetryTest",
           "WebUploadStreamTest", "WebUploadDiskBudgetTest", "PictureInPicturePolicyTest"]


def run(command, log, environment):
    result = subprocess.run(command, cwd=ROOT, env=environment, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log.write_text(result.stdout, encoding="utf-8")
    print(result.stdout, end="")
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
    output = ROOT / "artifacts/pr171/uploads/unit"
    output.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    if args.jdk:
        environment["JAVA_HOME"] = str(args.jdk.resolve())
    cp = os.pathsep.join(map(str, [junit, hamcrest]))
    generated = output / "generated"
    run([sys.executable, "-B", str(ROOT / "tools/prepare-ui-languages.py"),
         "--output", str(generated)], output / "language.log", environment)
    sourcepath = os.pathsep.join(map(str, [ROOT / "app/src/main/java",
                                         generated]))
    sources = [str(ROOT / "app/src/test/java/com/deepseekharness/app/util" / (name + ".java"))
               for name in CLASSES]
    run([javac, "-encoding", "UTF-8", "--release", "17", "-d", str(output),
         "-cp", cp, "-sourcepath", sourcepath] + sources, output / "compile.log", environment)
    run([java, "-cp", os.pathsep.join([str(output), cp]), "org.junit.runner.JUnitCore"] +
        ["com.deepseekharness.app.util." + name for name in CLASSES],
        output / "junit.log", environment)
    run([gradle, "-p", str(ROOT / "tools/fixtures/uploads-framework"),
         "--gradle-user-home", str(home), "test", "--no-daemon", "--console=plain"],
        output.parent / "framework.log", environment)
    print("PASS_UPLOAD_FRAMEWORK_HOST: real View/AFD behavior; cache syscall model; no device/APK")


if __name__ == "__main__":
    main()
