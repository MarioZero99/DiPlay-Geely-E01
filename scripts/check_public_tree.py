#!/usr/bin/env python3
"""Fail CI if what this tree must not publish enters it.

Three things are forbidden, and the third is the one that is easy to do by accident: the adapter
stack lives outside this repository and is mounted by -PadapterDir, so it leaves no trace here
to notice when it comes back. A merge or a cherry-pick of the branch that carried it would restore
the paths, and the vendored BTstack in it is under a personal, non-commercial licence — publishing
a tree that carried it is the whole reason it moved out.
"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
# This file names the marker it looks for, so it matches itself. A checker does not need checking.
this_file = str(Path(__file__).resolve().relative_to(root))
names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')

# The vendored BTstack, by its copyright holder's name rather than by its path: a file that carries it
# is the licence problem wherever it lands and whatever it is called, which a path list cannot promise.
vendored_btstack = re.compile(rb'BlueKitchen')

# And the paths the adapter stack left behind. A tracked file under one of these is a file that came
# back, and it is checked by path as well because not every adapter source is BTstack's.
adapter_prefixes = (
    'shared/src/hostTest/',
    'shared/src/main/java/com/shilapi/xcertplay/adapter/',
    'shared/src/main/java/com/shilapi/xcertplay/transport/hci/',
    'shared/src/main/jni/btstack/',
    'shared/src/test/java/com/shilapi/xcertplay/transport/hci/',
)
adapter_files = {
    'shared/src/main/jni/btstack_android_usb_transport.c',
    'shared/src/main/jni/hci_transport_android_usb.h',
    'tools/build-host-btstack.sh',
}

failures = []
for name in filter(None, names):
    path = root / name
    if name == this_file or not path.is_file():
        continue
    if '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name + ': credential or distribution file')
    elif name in adapter_files or name.startswith(adapter_prefixes):
        failures.append(name + ': belongs to the adapter stack, which is mounted, not committed')
    else:
        content = path.read_bytes()
        if private_block.search(content):
            failures.append(name + ': private-key block')
        elif vendored_btstack.search(content):
            failures.append(name + ': carries the vendored BTstack, which this tree must not publish')
if failures:
    raise SystemExit('Forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: no credentials, no private keys, no adapter stack.')
