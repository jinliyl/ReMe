"""Exercise a running Android emulator against a local diary service and real speech.

Build both debug APKs first. Supply a private connection file from android_app.serve,
start the AVD with audio and authenticated gRPC enabled, and provide a mono PCM WAV.
The optional grpcio-tools package generates bindings from the installed SDK itself.
"""

import argparse
from contextlib import contextmanager
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import wave

PACKAGE = "io.agentscope.reme.diary"
EVIDENCE_FILES = [
    "integration-result.json",
    "android-home.png",
    "android-conversation.png",
    "android-tasks.png",
    "android-task-completed.png",
    "android-recording.png",
]
OPTIONAL_EVIDENCE_FILES = ["android-notification.png"]


def adb_command(sdk, serial, *arguments, **kwargs):
    """Run only explicit commands on the selected test emulator."""
    return subprocess.run(
        [str(sdk / "platform-tools/adb"), "-s", serial, *arguments],
        check=True,
        capture_output=True,
        timeout=30,
        **kwargs,
    )


@contextmanager
def emulator_stub(sdk, serial):
    """Bind SDK-generated control stubs to this emulator's private bearer token."""
    import grpc
    import grpc_tools
    from grpc_tools import protoc

    console_port = serial.removeprefix("emulator-")
    directory = Path.home() / "Library/Caches/TemporaryItems/avd/running"
    selected = None
    for path in directory.glob("pid_*.ini"):
        values = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
        if values.get("port.serial") == console_port:
            selected = values
            break
    if not selected or not selected.get("grpc.token"):
        raise RuntimeError("Start this AVD with -grpc <port> -grpc-use-token; its private discovery record is required")
    with tempfile.TemporaryDirectory(prefix="reme-emulator-proto-") as temporary:
        include = str(sdk / "emulator/lib")
        status = protoc.main(
            [
                "grpc_tools.protoc",
                f"-I{include}",
                f"-I{Path(grpc_tools.__file__).parent / '_proto'}",
                f"--python_out={temporary}",
                f"--grpc_python_out={temporary}",
                str(sdk / "emulator/lib/emulator_controller.proto"),
            ],
        )
        if status:
            raise RuntimeError("SDK protobuf generation failed")
        sys.path.insert(0, temporary)
        try:
            messages = importlib.import_module("emulator_controller_pb2")
            services = importlib.import_module("emulator_controller_pb2_grpc")
            with grpc.insecure_channel(f"127.0.0.1:{int(selected['grpc.port'])}") as channel:
                yield services.EmulatorControllerStub(channel), messages, [
                    ("authorization", "Bearer " + selected["grpc.token"]),
                ]
        finally:
            sys.path.remove(temporary)


def inject_audio(sdk, serial, audio_file):
    """Inject one WAV once, paced by the emulator's microphone queue."""
    with emulator_stub(sdk, serial) as (stub, messages, metadata):
        stub.setMicrophoneState(messages.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=10)
        with wave.open(str(audio_file), "rb") as recording:
            if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate()) != (1, 2, 16000):
                raise ValueError("test audio must be mono PCM16 WAV at 16000 Hz")
            audio_format = messages.AudioFormat(
                samplingRate=16000,
                channels=messages.AudioFormat.Mono,
                format=messages.AudioFormat.AUD_FMT_S16,
            )

            def packets():
                while data := recording.readframes(1600):
                    yield messages.AudioPacket(format=audio_format, audio=data)

            stub.injectAudio(packets(), metadata=metadata, timeout=60)


def main():
    """Install the APKs, run real Compose tests, and export sanitized evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "~/Library/Android/sdk")))
    parser.add_argument("--serial", default="emulator-5556")
    parser.add_argument("--connection-file", type=Path, required=True)
    parser.add_argument("--audio-file", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, default=Path("/tmp/reme-android-emulator-report"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("select an emulator; this test injects synthetic microphone input")
    sdk = args.sdk.expanduser().resolve()
    root = Path(__file__).resolve().parents[2]
    args.report_dir.mkdir(parents=True, exist_ok=True)
    for name in EVIDENCE_FILES + OPTIONAL_EVIDENCE_FILES:
        (args.report_dir / name).unlink(missing_ok=True)

    def call(*words, **kwargs):
        return adb_command(sdk, args.serial, *words, **kwargs)

    for apk in ["debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
        call("install", "-r", str(root / "reme_android/app/build/outputs/apk" / apk))
    connection = json.loads(args.connection_file.read_text(encoding="utf-8"))
    from urllib.parse import urlsplit

    origin = urlsplit(connection["origin"])
    if origin.hostname not in {"127.0.0.1", "localhost"} or origin.scheme != "http":
        parser.error("use a private connection file for the isolated local HTTP test service")
    port = origin.port or 80
    call("reverse", f"tcp:{port}", f"tcp:{port}")
    call("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    call("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    call(
        "shell", "run-as", PACKAGE, "rm", "-f", *(f"files/{name}" for name in EVIDENCE_FILES + OPTIONAL_EVIDENCE_FILES)
    )
    call(
        "shell",
        f"run-as {PACKAGE} sh -c 'cat > files/integration-connection.json'",
        input=args.connection_file.read_bytes(),
    )
    call("shell", "run-as", PACKAGE, "chmod", "600", "files/integration-connection.json")
    call("shell", "run-as", PACKAGE, "rm", "-f", "files/microphone-ready")
    call("shell", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
    call("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    test_class = f"{PACKAGE}.LocalIntegrationTest"
    command = [
        str(sdk / "platform-tools/adb"),
        "-s",
        args.serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        test_class,
        f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner",
    ]
    started, injected = time.time(), False
    with (args.report_dir / "instrumentation.log").open("wb") as output:
        with subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT) as process:
            try:
                while process.poll() is None:
                    if time.time() - started > 900:
                        raise TimeoutError("Android real-provider tests did not finish within 15 minutes")
                    if not injected:
                        try:
                            result = call("exec-out", "run-as", PACKAGE, "cat", "files/microphone-ready")
                            ready = int(result.stdout.strip()) / 1000
                        except (subprocess.CalledProcessError, ValueError):
                            ready = 0
                        if ready >= started:
                            inject_audio(sdk, args.serial, args.audio_file)
                            injected = True
                            print("Injected test speech into the emulator microphone", flush=True)
                    time.sleep(1)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
    for name in EVIDENCE_FILES + OPTIONAL_EVIDENCE_FILES:
        try:
            result = call("exec-out", "run-as", PACKAGE, "cat", f"files/{name}")
            if name.endswith(".png"):
                if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                    continue
            else:
                json.loads(result.stdout)
            (args.report_dir / name).write_bytes(result.stdout)
        except (subprocess.CalledProcessError, ValueError):
            pass
    log = (args.report_dir / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
    report = args.report_dir / "integration-result.json"
    if (
        process.returncode
        or "OK (2 tests)" not in log
        or not all((args.report_dir / name).is_file() for name in EVIDENCE_FILES)
    ):
        raise RuntimeError(f"Android tests failed; inspect {args.report_dir / 'instrumentation.log'}")
    result = json.loads(report.read_text(encoding="utf-8"))
    if not injected or not all(
        result.get(key) is True
        for key in [
            "success",
            "real_model",
            "native_notification",
            "task_completion",
            "emulated_microphone_real_asr",
            "native_tts_playback",
        ]
    ):
        raise RuntimeError("Android tests did not verify the complete UI, reminder and microphone paths")
    print(f"Passed real Android UI, reminder and voice tests; evidence: {args.report_dir}")


if __name__ == "__main__":
    main()
