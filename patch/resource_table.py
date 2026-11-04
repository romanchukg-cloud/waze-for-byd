# SPDX-License-Identifier: GPL-3.0-only
"""Append one xml resource to a resources.arsc without rebuilding the table.

Only used with the SHA-256-pinned Waze input in build.py. Existing strings, keys and resource IDs stay
as they are; the new entry takes the next free ID of the xml type.
"""
import struct


def _u16(buf, pos):
    return struct.unpack_from('<H', buf, pos)[0]


def _u32(buf, pos):
    return struct.unpack_from('<I', buf, pos)[0]


def _append_utf8(pool, value):
    """Return the UTF-8 string pool chunk with value appended, and the new string's index."""
    assert _u16(pool, 0) == 1 and _u16(pool, 2) == 28
    count, styles, flags, start, style_start = struct.unpack_from('<5I', pool, 8)
    assert flags & 0x100 and start == 28 + 4 * (count + styles) and bool(styles) == bool(style_start)
    data = value.encode('ascii')
    assert len(data) < 128
    # Layout: string offsets, style offsets, strings, styles. Strings end where the styles begin.
    end = style_start or len(pool)
    assert not end % 4
    added = bytes([len(data), len(data)]) + data + b'\0'
    added += b'\0' * (-len(added) % 4)
    offset = 28 + 4 * count
    out = bytearray(pool[:offset]) + struct.pack('<I', end - start) + pool[offset:end] + added + pool[end:]
    struct.pack_into('<I', out, 4, len(out))
    struct.pack_into('<I', out, 8, count + 1)
    struct.pack_into('<I', out, 16, flags & ~1)  # Appending invalidates SORTED.
    struct.pack_into('<I', out, 20, start + 4)
    if styles: struct.pack_into('<I', out, 24, style_start + 4 + len(added))
    return out, count


def _type_index(pool, name):
    count, _, flags, start, _ = struct.unpack_from('<5I', pool, 8)
    assert not flags & 0x100
    for i in range(count):
        at = start + _u32(pool, 28 + 4 * i)
        length = _u16(pool, at)
        assert length < 0x8000
        if bytes(pool[at + 2:at + 2 + 2 * length]).decode('utf-16le') == name:
            return i + 1
    raise ValueError(f'No resource type {name}')


def add_xml_resource(data, key, path):
    """Return (new resources.arsc, resource ID) with key added to the xml type, pointing at path."""
    assert _u16(data, 0) == 2 and _u16(data, 2) == 12 and _u32(data, 4) == len(data) and _u32(data, 8) == 1
    pool_size = _u32(data, 12 + 4)
    global_pool, path_index = _append_utf8(bytes(data[12:12 + pool_size]), path)
    package = 12 + pool_size
    assert _u16(data, package) == 0x200 and _u32(data, package + 4) == len(data) - package
    header = _u16(data, package + 2)
    package_id = _u32(data, package + 8)
    type_strings, key_strings = _u32(data, package + 268), _u32(data, package + 276)
    assert header == type_strings < key_strings
    type_pool = data[package + type_strings:package + key_strings]
    assert _u32(type_pool, 4) == len(type_pool)
    type_id = _type_index(type_pool, 'xml')
    key_size = _u32(data, package + key_strings + 4)
    key_pool, key_index = _append_utf8(bytes(data[package + key_strings:package + key_strings + key_size]), key)
    body = bytearray(data[package:package + key_strings]) + key_pool
    position, entry, specs, types = package + key_strings + key_size, None, 0, 0
    while position < len(data):
        kind, chunk_header, size = _u16(data, position), _u16(data, position + 2), _u32(data, position + 4)
        chunk = bytearray(data[position:position + size])
        if kind == 0x202 and chunk[8] == type_id:
            count = _u32(chunk, 12)
            assert size == chunk_header + 4 * count
            chunk += b'\0\0\0\0'
            struct.pack_into('<I', chunk, 4, len(chunk))
            struct.pack_into('<I', chunk, 12, count + 1)
            entry = count
            specs += 1
        elif kind == 0x201 and chunk[8] == type_id:
            count, entries = _u32(chunk, 12), _u32(chunk, 16)
            assert not chunk[9] and entries == chunk_header + 4 * count and not size % 4
            chunk = chunk[:entries] + struct.pack('<I', size - entries) + chunk[entries:]
            chunk += struct.pack('<HHI', 8, 0, key_index) + struct.pack('<HBBI', 8, 0, 3, path_index)
            struct.pack_into('<I', chunk, 4, len(chunk))
            struct.pack_into('<I', chunk, 12, count + 1)
            struct.pack_into('<I', chunk, 16, entries + 4)
            types += 1
        body += chunk
        position += size
    assert specs == 1 and types == 1 and position == len(data)
    struct.pack_into('<I', body, 4, len(body))
    out = bytearray(data[:12]) + global_pool + body
    struct.pack_into('<I', out, 4, len(out))
    return bytes(out), package_id << 24 | type_id << 16 | entry
