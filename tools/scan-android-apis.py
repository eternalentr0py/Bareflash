#!/usr/bin/env python3
"""Fails if compiled classes call JDK 9+ methods that Android may not have.

Why: compiled against the JDK 17 API, `byteBuffer.position(n)` or
`charBuffer.flip()` is emitted with a JDK 9 return type (ByteBuffer.position(I)
Ljava/nio/ByteBuffer;). Older Android only has the Buffer versions, so the app
dies with NoSuchMethodError, which is an Error and bypasses every catch block.
The protocol modules are built with -Xjdk-release=8 to prevent this; this scan
is a second, independent check on the compiled bytecode.

Usage: scan-android-apis.py <classes-dir> [<classes-dir> ...]
Exit status 1 if any such call is found.
"""
import struct, sys, os, re

def parse(path):
    d = open(path, 'rb').read()
    assert d[:4] == b'\xca\xfe\xba\xbe'
    i = 8
    n = struct.unpack('>H', d[i:i+2])[0]; i += 2
    cp = [None]*n
    k = 1
    while k < n:
        t = d[i]; i += 1
        if t == 1:
            l = struct.unpack('>H', d[i:i+2])[0]; i += 2
            cp[k] = ('utf8', d[i:i+l].decode('utf-8', 'replace')); i += l
        elif t in (3, 4): cp[k] = ('num',); i += 4
        elif t in (5, 6): cp[k] = ('num',); i += 8; k += 1
        elif t == 7: cp[k] = ('class', struct.unpack('>H', d[i:i+2])[0]); i += 2
        elif t == 8: cp[k] = ('str',); i += 2
        elif t in (9, 10, 11): cp[k] = ('ref', t, *struct.unpack('>HH', d[i:i+4])); i += 4
        elif t == 12: cp[k] = ('nat', *struct.unpack('>HH', d[i:i+4])); i += 4
        elif t == 15: cp[k] = ('mh',); i += 3
        elif t == 16: cp[k] = ('mt',); i += 2
        elif t in (17, 18): cp[k] = ('indy',); i += 4
        elif t in (19, 20): cp[k] = ('mod',); i += 2
        else: raise Exception('bad tag %d' % t)
        k += 1
    def utf(ix): return cp[ix][1]
    out = []
    for e in cp:
        if e and e[0] == 'ref' and e[1] in (10, 11):
            cls = utf(cp[e[2]][1]); nat = cp[e[3]]
            out.append((cls, utf(nat[1]), utf(nat[2])))
    return out

BAD = re.compile(r'^java/nio/(Byte|Char|Short|Int|Long|Float|Double)Buffer$')
BUFFER_METHODS = {'flip', 'position', 'limit', 'clear', 'rewind', 'mark', 'reset', 'slice', 'duplicate'}
hits = {}
scanned = 0
for root in sys.argv[1:]:
    if not os.path.isdir(root):
        print('error: %s is not a directory (was the module built?)' % root)
        sys.exit(2)
    for dp, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith('.class'): continue
            scanned += 1
            for cls, name, desc in parse(os.path.join(dp, f)):
                if BAD.match(cls) and name in BUFFER_METHODS and desc.endswith(cls + ';') :
                    hits.setdefault((cls, name, desc), set()).add(f[:-6])
                if cls == 'java/util/concurrent/ConcurrentHashMap' and name in ('keySet',):
                    hits.setdefault((cls, name, desc), set()).add(f[:-6])
for k, v in sorted(hits.items()):
    print('%s.%s%s  <- %s' % (k[0], k[1], k[2], ', '.join(sorted(v))))
if scanned == 0:
    print('error: no class files found; refusing to report a clean result')
    sys.exit(2)
print('scanned %d classes, violations: %d' % (scanned, len(hits)))
sys.exit(1 if hits else 0)
