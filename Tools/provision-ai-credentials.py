#!/usr/bin/env python3
"""Provision a personal debug install from .env without embedding keys in the APK."""
import argparse
import pathlib
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.gumlet.gram.beta')
args = parser.parse_args()
values = {}
for line in (pathlib.Path(__file__).resolve().parents[1] / '.env').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip().strip('\"\'')
keys = ('GEMINI_API_KEY', 'GROQ_API_KEY')
if any(not values.get(key) for key in keys):
    raise SystemExit('Both GEMINI_API_KEY and GROQ_API_KEY must be set in .env')
payload = ''.join(f'{key}={values[key]}\n' for key in keys).encode()
command = ('umask 077; mkdir -p no_backup && cat > no_backup/ai.properties.tmp '
           '&& mv no_backup/ai.properties.tmp no_backup/ai.properties')
subprocess.run(['adb', '-s', args.serial, 'shell', '-T', 'run-as', args.package,
                'sh', '-c', shlex.quote(command)], input=payload, check=True)
print('Provisioned both provider keys into private, backup-excluded app storage.')
