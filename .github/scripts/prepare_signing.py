"""Restore a fixed signing key from one repository secret, without logging it."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def prepare():
    raw = os.environ.get("ANDROID_SIGNING_BUNDLE", "")
    if not raw:
        raise ValueError("Add the ANDROID_SIGNING_BUNDLE repository secret, then rerun this workflow.")
    bundle = json.loads(raw)
    values = {
        "ANDROID_KEYSTORE_PASSWORD": bundle["store_password"],
        "ANDROID_KEY_ALIAS": bundle["key_alias"],
        "ANDROID_KEY_PASSWORD": bundle["key_password"],
    }
    for value in values.values():
        if not isinstance(value, str) or not value or any(c in value for c in "\r\n\x00"):
            raise ValueError("Invalid signing configuration.")
    keystore = base64.b64decode(bundle["keystore_base64"], validate=True)
    for value in [*values.values(), bundle["keystore_base64"]]:
        print("::add-mask::" + value, flush=True)
    target = Path(os.environ["RUNNER_TEMP"]) / "companion-signing.p12"
    target.write_bytes(keystore)
    target.chmod(0o600)
    try:
        env = {**os.environ, **values}
        result = subprocess.run(
            ["keytool", "-exportcert", "-keystore", str(target), "-storetype", "PKCS12",
             "-storepass:env", "ANDROID_KEYSTORE_PASSWORD", "-alias", values["ANDROID_KEY_ALIAS"]],
            env=env, capture_output=True, check=True,
        )
        expected = (Path(__file__).resolve().parents[1] / "signing-certificate.sha256").read_text().strip()
        if hashlib.sha256(result.stdout).hexdigest() != expected:
            raise ValueError("Signing certificate does not match this application's fixed certificate.")
        with open(os.environ["GITHUB_ENV"], "a") as output:
            for key, value in {**values, "ANDROID_KEYSTORE_PATH": str(target)}.items():
                output.write(f"{key}={value}\n")
    except Exception:
        target.unlink(missing_ok=True)
        raise
    print("Fixed signing certificate verified.")


if __name__ == "__main__":
    try:
        prepare()
    except Exception as error:
        # Never print parser/subprocess exception details containing secret values.
        message = str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else "Invalid signing bundle or keystore."
        if type(error) is not ValueError:
            message = "Invalid signing bundle or keystore."
        print("::error::" + message, file=sys.stderr)
        sys.exit(1)
