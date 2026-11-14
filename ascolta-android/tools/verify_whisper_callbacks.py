#!/usr/bin/env python3
"""Check JNI-only Whisper callbacks in the actual optimized APK (no Android SDK needed)."""
import struct
import sys
import zipfile


def check(apk):
    implementations = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith('classes') and name.endswith('.dex')):
                continue
            data = archive.read(name)
            def uint(offset):
                return struct.unpack_from('<I', data, offset)[0]
            def uleb(offset):
                value = shift = 0
                while True:
                    byte = data[offset]
                    offset += 1
                    value |= (byte & 127) << shift
                    if byte < 128:
                        return value, offset
                    shift += 7
            strings = []
            for index in range(uint(56)):
                offset = uint(uint(60) + index * 4)
                _, offset = uleb(offset)
                strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
            types = [strings[uint(uint(68) + i * 4)] for i in range(uint(64))]
            methods = [struct.unpack_from('<HHI', data, uint(92) + i * 8) for i in range(uint(88))]
            for index in range(uint(96)):
                offset = uint(100) + index * 32
                interfaces = uint(offset + 12)
                if not interfaces:
                    continue
                implemented = [types[struct.unpack_from('<H', data, interfaces + 4 + i * 2)[0]] for i in range(uint(interfaces))]
                if 'Lcom/ascolta/app/whisper/TranscribeListener;' not in implemented:
                    continue
                body = uint(offset + 24)
                assert body, 'Listener implementation lost its methods'
                counts = []
                for _ in range(4):
                    count, body = uleb(body)
                    counts.append(count)
                for _ in range(counts[0] + counts[1]):
                    _, body = uleb(body)
                    _, body = uleb(body)
                found = set()
                for count in counts[2:]:
                    method = 0
                    for _ in range(count):
                        delta, body = uleb(body)
                        method += delta
                        _, body = uleb(body)
                        code, body = uleb(body)
                        if code:
                            _, proto, method_name = methods[method]
                            p = uint(76) + proto * 12
                            params = uint(p + 8)
                            args = '' if not params else ''.join(types[struct.unpack_from('<H', data, params + 4 + i * 2)[0]] for i in range(uint(params)))
                            found.add((strings[method_name], '(' + args + ')' + types[uint(p + 4)]))
                required = {('onProgress', '(I)V'), ('onSegment', '(JJLjava/lang/String;[Ljava/lang/String;[J)V')}
                missing = required - found
                assert not missing, f'{types[uint(offset)]}: JNI callback methods missing: {missing}'
                implementations.append(types[uint(offset)])
    assert implementations, 'No preserved TranscribeListener implementation in APK'
    print('Verified JNI callback names/signatures in optimized APK:', ', '.join(implementations))


if __name__ == '__main__':
    check(sys.argv[1])
