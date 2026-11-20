"""Run the diary locally or on a server without copying provider credentials."""

import argparse
import json
import os
from pathlib import Path
import secrets

from ...application import Application
from ...config import resolve_app_config
from ...utils import load_env


def main():
    """Start the HTTP service with optional private emulator connection output."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--workspace", type=Path, default=Path("~/.reme-android"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=2333)
    parser.add_argument(
        "--connection-file",
        type=Path,
        help="Private local test credentials, outside the diary workspace",
    )
    args = parser.parse_args()
    if args.env_file:
        if not args.env_file.is_file():
            parser.error("environment file does not exist")
        load_env(args.env_file)
    workspace = args.workspace.expanduser().resolve()
    if args.connection_file:
        connection_file = args.connection_file.expanduser().resolve()
        if connection_file.is_relative_to(workspace):
            parser.error("connection-file must stay outside the model-readable diary workspace")
        os.environ.setdefault("ANDROID_APP_TOKEN", secrets.token_urlsafe(32))
        connection_file.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(connection_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump({"origin": f"http://127.0.0.1:{args.port}/", "token": os.environ["ANDROID_APP_TOKEN"]}, handle)
    config = resolve_app_config(
        config="android_app",
        log_config=False,
        workspace_dir=str(workspace),
        service={"host": args.host, "port": args.port},
    )
    Application(**config).run_app()


if __name__ == "__main__":
    main()
