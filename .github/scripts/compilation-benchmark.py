import json
import os
import subprocess
import time
from pathlib import Path

source = Path("src/en/av1encodes/src/eu/kanade/tachiyomi/animeextension/en/av1encodes/AV1Encodes.kt")
source.write_text(source.read_text().replace('override val name = "AV1Encodes"', 'override val name = "AV1Encodes benchmark"'))

if os.environ["BENCHMARK_SCOPE"] == "subset":
    settings = Path("settings.gradle.kts")
    settings.write_text(settings.read_text().replace("\nloadAllIndividualExtensions()\n", '\nloadIndividualExtension("en", "av1encodes")\n'))

started = time.monotonic()
process = subprocess.Popen(
    ["./gradlew", ":src:en:av1encodes:assembleDebug", "--console=plain"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)
lines = []
for line in process.stdout:
    print(line, end="", flush=True)
    lines.append(line)
exit_code = process.wait()
result = {
    "scope": os.environ["BENCHMARK_SCOPE"],
    "cache": os.environ["BENCHMARK_CACHE"],
    "sample": int(os.environ["BENCHMARK_SAMPLE"]),
    "elapsed_seconds": round(time.monotonic() - started, 3),
    "exit_code": exit_code,
    "core_compile": next((line.strip() for line in lines if line.startswith("> Task :core:compileDebugKotlin")), None),
    "extension_compile": next((line.strip() for line in lines if line.startswith("> Task :src:en:av1encodes:compileDebugKotlin")), None),
    "summary": [line.strip() for line in lines if "BUILD SUCCESSFUL" in line or "actionable tasks:" in line],
}
Path("benchmark-result.json").write_text(json.dumps(result, indent=2) + "\n")
Path("benchmark-build.log").write_text("".join(lines))
print(json.dumps(result, indent=2))
raise SystemExit(exit_code)
