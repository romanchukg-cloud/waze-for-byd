# SPDX-License-Identifier: GPL-3.0-only
"""Set an application label in binary Android XML without rebuilding resources.

Only used with the SHA-256-pinned Waze input in build.py. Existing string indexes
and XML nodes remain intact; append one string and change one label attribute.
"""
import struct


def rename_application(data: bytes, label: str) -> bytes:
    def u16(buf, pos):
        return struct.unpack_from('<H', buf, pos)[0]

    def u32(buf, pos):
        return struct.unpack_from('<I', buf, pos)[0]

    if u16(data, 0) != 3 or u32(data, 4) != len(data):
        raise ValueError('Expected complete binary Android XML')
    chunks = []
    pos = u16(data, 2)
    while pos < len(data):
        size = u32(data, pos + 4)
        if size < 8 or pos + size > len(data):
            raise ValueError('Invalid XML chunk')
        chunks.append(bytearray(data[pos:pos + size]))
        pos += size
    pools = [chunk for chunk in chunks if u16(chunk, 0) == 1]
    if len(pools) != 1:
        raise ValueError('Expected one string pool')
    pool = pools[0]
    header = u16(pool, 2)
    count, styles, flags, start, style_start = struct.unpack_from('<5I', pool, 8)
    if styles or style_start or header != 28:
        raise ValueError('Styled/extended string pools are unsupported')
    utf8 = bool(flags & 0x100)

    def read_length(offset, width):
        value = pool[offset] if width == 1 else u16(pool, offset)
        high = 0x80 if width == 1 else 0x8000
        if value & high:
            low = pool[offset + width] if width == 1 else u16(pool, offset + width)
            return ((value & (high - 1)) << (width * 8)) | low, offset + 2 * width
        return value, offset + width

    strings = []
    for i in range(count):
        offset = start + u32(pool, header + 4 * i)
        length, offset = read_length(offset, 1 if utf8 else 2)
        if utf8:
            length, offset = read_length(offset, 1)
        strings.append(bytes(pool[offset:offset + length * (1 if utf8 else 2)]).decode('utf-8' if utf8 else 'utf-16le'))
    utf16_length = len(label.encode('utf-16le')) // 2
    encoded = label.encode('utf-8' if utf8 else 'utf-16le')
    if utf16_length >= 128 or (utf8 and len(encoded) >= 128):
        raise ValueError('Label must fit in short string lengths')
    addition = (bytes([utf16_length, len(encoded)]) + encoded + b'\0'
                if utf8 else struct.pack('<H', utf16_length) + encoded + b'\0\0')
    new_pool = pool[:start] + struct.pack('<I', len(pool) - start) + pool[start:] + addition
    new_pool.extend(b'\0' * (-len(new_pool) % 4))
    struct.pack_into('<I', new_pool, 4, len(new_pool))
    struct.pack_into('<I', new_pool, 8, count + 1)
    struct.pack_into('<I', new_pool, 16, flags & ~1)  # Appending invalidates SORTED.
    struct.pack_into('<I', new_pool, 20, start + 4)
    chunks[chunks.index(pool)] = new_pool
    changed = 0
    for chunk in chunks:
        if u16(chunk, 0) != 0x102:  # RES_XML_START_ELEMENT_TYPE
            continue
        ext = u16(chunk, 2)
        if strings[u32(chunk, ext + 4)] != 'application':
            continue
        attr_start, attr_size, attr_count = struct.unpack_from('<3H', chunk, ext + 8)
        if attr_size != 20:
            raise ValueError('Unexpected XML attribute size')
        for i in range(attr_count):
            attr = ext + attr_start + i * attr_size
            namespace, name = struct.unpack_from('<2I', chunk, attr)
            if (strings[name] == 'label' and namespace != 0xffffffff
                    and strings[namespace] == 'http://schemas.android.com/apk/res/android'):
                struct.pack_into('<I', chunk, attr + 8, count)  # rawValue
                struct.pack_into('<HBBI', chunk, attr + 12, 8, 0, 3, count)  # TYPE_STRING
                changed += 1
    if changed != 1:
        raise ValueError(f'Expected one application label, found {changed}')
    result = bytearray(data[:u16(data, 2)]) + b''.join(chunks)
    struct.pack_into('<I', result, 4, len(result))
    return bytes(result)


def set_version_code(data: bytes, code: int) -> bytes:
    """Replace the manifest's android:versionCode; the chunk layout and string pool stay the same."""
    def u16(buf, pos):
        return struct.unpack_from('<H', buf, pos)[0]

    def u32(buf, pos):
        return struct.unpack_from('<I', buf, pos)[0]

    out = bytearray(data)
    if u16(out, 0) != 3 or u32(out, 4) != len(out):
        raise ValueError('Expected complete binary Android XML')
    pos = u16(out, 2)
    resource_ids = []
    changed = 0
    while pos < len(out):
        kind, header, size = u16(out, pos), u16(out, pos + 2), u32(out, pos + 4)
        if kind == 0x180:  # RES_XML_RESOURCE_MAP_TYPE
            resource_ids = [u32(out, pos + header + 4 * i) for i in range((size - header) // 4)]
        elif kind == 0x102 and not changed:  # the first element is <manifest>
            attr_start, attr_size, attr_count = struct.unpack_from('<3H', out, pos + header + 8)
            for i in range(attr_count):
                attr = pos + header + attr_start + i * attr_size
                name = u32(out, attr + 4)
                if name < len(resource_ids) and resource_ids[name] == 0x0101021b:  # android:versionCode
                    if out[attr + 15] != 0x10:  # TYPE_INT_DEC
                        raise ValueError('versionCode is not a decimal integer')
                    struct.pack_into('<I', out, attr + 16, code)
                    changed += 1
            if not changed:
                raise ValueError('No versionCode on <manifest>')
        pos += size
    if changed != 1:
        raise ValueError(f'Expected one versionCode, found {changed}')
    return bytes(out)
