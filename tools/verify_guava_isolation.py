#!/usr/bin/env python3
"""Read-only R8/APK verification: all DEX types, mapped Guava and Media3 invoke sites.

Usage: python3 tools/verify_guava_isolation.py --apk APP.apk --mapping mapping.txt
       --dexdump ANDROID_SDK/build-tools/34.0.0/dexdump --report report.json
       [--apksigner .../apksigner --baseline-apk previous.apk]
No APK code is executed, and no device is contacted.
"""
import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

PREFIX = "com.lizongying.mytv0.m17isolated."
DIAGNOSTIC_LITERALS = {
    "com.google.common.base.Strings": "Ljava/util/logging/Logger;.getLogger:",
    "com.google.common.util.concurrent.": "Ljava/lang/String;.startsWith:",
}


def descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def dex_types(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX header")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    string_count, string_offset = u32(56), u32(60)
    strings = []
    for index in range(string_count):
        offset = u32(string_offset + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1  # Skip the final ULEB128 UTF-16 length byte.
        end = data.index(b"\0", offset)
        # Namespace and descriptor evidence is ASCII; other MUTF-8 is immaterial.
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    classes = {types[u32(u32(100) + index * 32)] for index in range(u32(96))}
    return types, classes, strings


def certificate(apksigner, apk):
    text = subprocess.check_output([str(apksigner), "verify", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: (\S+)", text)
    if not certificates:
        raise ValueError("APK signing certificate missing")
    return sorted(certificates)


def verify(args):
    mapping = {}
    mapping_owner = None
    builder_implementations = set()
    for line in args.mapping.read_text().splitlines():
        found = re.match(r"^(\S+) -> (\S+):$", line)
        if found:
            mapping[found[1]] = found[2]
            mapping_owner = found[2]
        if "com.google.common.collect.ImmutableMap$Builder.buildOrThrow(" in line:
            builder_implementations.add((mapping_owner, line.rsplit(" -> ", 1)[-1].strip()))
    guava = {old: new for old, new in mapping.items() if old.startswith("com.google.common.")}
    media3 = {old: new for old, new in mapping.items() if old.startswith("androidx.media3.")}
    errors = []
    if not guava or not media3:
        errors.append("R8 mapping lacks Guava or Media3 classes")
    # R8 retains REMOVED placeholders for retracing optimized-away class frames.
    removed_guava = {old: new for old, new in guava.items() if new.startswith("R8$$REMOVED$$CLASS$$")}
    unisolated = {old: new for old, new in guava.items() if not new.startswith(PREFIX) and old not in removed_guava}
    if unisolated:
        errors.append("Some Guava mappings were not moved to the isolated package")
    guava_descriptors = {descriptor(name) for name in guava.values()}
    media3_descriptors = {descriptor(name) for name in media3.values()}
    inverse_media3 = {}
    for old, new in media3.items():
        inverse_media3.setdefault(descriptor(new), []).append(old)
    inverse_guava = {}
    for old, new in guava.items():
        inverse_guava.setdefault(descriptor(new), []).append(old)
    all_classes, old_types, old_strings = set(), set(), set()
    invoke_count = 0
    caller_classes = set()
    examples = []
    dex_results = []
    diagnostic_uses = {literal: 0 for literal in DIAGNOSTIC_LITERALS}
    unverified_literals = set()
    with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory(prefix="verify-guava-") as temporary:
        names = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        if not names:
            errors.append("APK contains no DEX files")
        for name in names:
            data = archive.read(name)
            types, classes, strings = dex_types(data)
            all_classes.update(classes)
            old_types.update(t for t in types if t.lstrip("[").startswith("Lcom/google/common/"))
            old_strings.update(s for s in strings if "com.google.common." in s or "com/google/common/" in s)
            dex_file = Path(temporary) / name
            dex_file.write_bytes(data)
            # Use the SDK disassembler to inspect actual invoke instructions, not string guesses.
            process = subprocess.Popen([str(args.dexdump), "-d", str(dex_file)], stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, text=True, errors="replace")
            current_class = None
            previous_literal = None
            dex_invokes = 0
            for line in process.stdout:
                if previous_literal:
                    if DIAGNOSTIC_LITERALS[previous_literal] in line:
                        diagnostic_uses[previous_literal] += 1
                    else:
                        unverified_literals.add(previous_literal)
                    previous_literal = None
                literal = re.search(r'const-string(?:/jumbo)? [^,]+, "([^"]+)"', line)
                if literal and literal[1] in DIAGNOSTIC_LITERALS:
                    previous_literal = literal[1]
                found = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
                if found:
                    current_class = found[1]
                if current_class not in media3_descriptors or ": invoke-" not in line:
                    continue
                target = re.search(r"\}, (L[^;]+;)\.([^\s]+)", line)
                if target and target[1] in guava_descriptors:
                    dex_invokes += 1
                    caller_classes.add(current_class)
                    if len(examples) < 12:
                        examples.append({"media3_original": inverse_media3[current_class],
                                         "caller_dex": current_class, "target_dex": target[1],
                                         "guava_original": inverse_guava[target[1]], "method": target[2]})
            stderr = process.stderr.read()
            if process.wait() != 0:
                raise RuntimeError("SDK dexdump failed: " + stderr[-500:])
            invoke_count += dex_invokes
            dex_results.append({"dex": name, "types": len(types), "defined_classes": len(classes),
                                "media3_to_relocated_guava_invokes": dex_invokes})
    if old_types:
        errors.append("DEX still links to the system-visible com.google.common namespace")
    unknown_strings = old_strings - DIAGNOSTIC_LITERALS.keys()
    unverified_literals.update(s for s in old_strings & DIAGNOSTIC_LITERALS.keys() if diagnostic_uses[s] == 0)
    if unknown_strings or unverified_literals:
        errors.append("DEX retains an unverified original Guava string")
    present_guava = guava_descriptors & all_classes
    if not present_guava or invoke_count == 0:
        errors.append("Missing evidence of packaged isolated Guava and rewritten Media3 calls")
    builder = guava.get("com.google.common.collect.ImmutableMap$Builder")
    if not builder_implementations or not all(owner.startswith(PREFIX) and descriptor(owner) in all_classes
                                             for owner, _ in builder_implementations):
        errors.append("ImmutableMap.Builder.buildOrThrow lacks a verified relocated implementation")
    # Android/JNI types must retain their externally looked-up names.
    required = ["com.lizongying.mytv0.MainActivity", "com.lizongying.mytv0.MyTVApplication",
                "com.lizongying.mytv0.InitializerProvider", "com.lizongying.mytv0.BootReceiver",
                "org.videolan.libvlc.LibVLC", "org.videolan.libvlc.VLCObject",
                "org.videolan.libvlc.Media", "org.videolan.libvlc.MediaPlayer",
                "org.videolan.libvlc.AWindow", "org.videolan.libvlc.interfaces.IMedia$Stats",
                "io.antmedia.rtmp_client.RtmpClient", "io.antmedia.rtmp_client.RTMPMuxer"]
    missing_required = [name for name in required if descriptor(name) not in all_classes]
    if missing_required:
        errors.append("An Android or JNI entry type was removed or renamed")
    certs = None
    same_certificate = None
    if args.apksigner:
        certs = certificate(args.apksigner, args.apk)
        if args.baseline_apk:
            same_certificate = certs == certificate(args.apksigner, args.baseline_apk)
            if not same_certificate:
                errors.append("Signing certificate differs from the previous APK")
    report = {"passed": not errors, "errors": errors, "apk": str(args.apk.resolve()),
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "mapping": str(args.mapping.resolve()), "guava_mapped_classes": len(guava),
              "guava_optimized_away_classes": len(removed_guava),
              "guava_defined_classes": len(present_guava), "unisolated_guava_mappings": unisolated,
              "immutable_map_builder": builder,
              "build_or_throw_relocated_implementations": sorted(builder_implementations),
              "old_guava_type_references": sorted(old_types),
              "old_guava_strings": sorted(old_strings), "media3_mapped_classes": len(media3),
              "verified_non_linking_diagnostic_literals": diagnostic_uses,
              "unverified_old_guava_strings": sorted(unknown_strings | unverified_literals),
              "media3_caller_classes": len(caller_classes), "rewritten_media3_guava_invokes": invoke_count,
              "invoke_examples": examples, "dex": dex_results,
              "missing_android_or_jni_classes": missing_required, "certificate_sha256": certs,
              "same_certificate_as_baseline": same_certificate}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("passed", "errors", "apk_sha256", "guava_mapped_classes",
          "guava_defined_classes", "rewritten_media3_guava_invokes", "same_certificate_as_baseline")}, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "mapping", "dexdump", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument("--baseline-apk", type=Path)
    raise SystemExit(verify(parser.parse_args()))
