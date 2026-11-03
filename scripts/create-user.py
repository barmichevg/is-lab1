#!/usr/bin/env python3
"""Create/update a PBKDF2 account in a local file. Passwords are read from the terminal."""
import base64, getpass, hashlib, os, pathlib, re, sys
if len(sys.argv)!=3:
    sys.exit('Usage: python3 scripts/create-user.py /path/to/users.properties username')
path, username=pathlib.Path(sys.argv[1]), sys.argv[2]
if not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', username):
    sys.exit('Use 1–64 letters, digits, _, . or - in the username')
password=getpass.getpass('Password: ')
if password != getpass.getpass('Repeat password: '):
    sys.exit('Passwords differ')
salt=os.urandom(16)
hash_value=hashlib.pbkdf2_hmac('sha256', password.encode(),salt,600000)
encoded='600000$'+base64.b64encode(salt).decode()+'$'+base64.b64encode(hash_value).decode()
lines=path.read_text().splitlines() if path.exists() else []
lines=[line for line in lines if not line.startswith(username+'=')]
lines.append(username+'='+encoded)
os.umask(0o077)
path.write_text('\n'.join(lines)+'\n')
path.chmod(0o600)
print('Account saved:',username)
