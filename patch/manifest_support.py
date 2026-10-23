# SPDX-License-Identifier: GPL-3.0-only
"""Append internal diagnostics components and the cluster declarations to the pinned binary manifest.

Preserve existing XML nodes, string indexes, resources and Waze components.
No resource rebuild is needed: the support UI uses Android framework widgets.
PACKAGE_USAGE_STATS is only declared; the owner grants Usage Access explicitly.
"""
import struct


def add_support_components(data):
    u16 = lambda b, p: struct.unpack_from('<H', b, p)[0]
    u32 = lambda b, p: struct.unpack_from('<I', b, p)[0]
    assert u16(data, 0) == 3 and u32(data, 4) == len(data)
    chunks, pos = [], u16(data, 2)
    while pos < len(data):
        size = u32(data, pos + 4)
        assert size >= 8 and pos + size <= len(data)
        chunks.append(bytearray(data[pos:pos + size]))
        pos += size
    pool = next(c for c in chunks if u16(c, 0) == 1)
    count, styles, flags, start, style_start = struct.unpack_from('<5I', pool, 8)
    assert u16(pool, 2) == 28 and not styles and not style_start
    utf8 = bool(flags & 0x100)

    def length(p, width):
        v = pool[p] if width == 1 else u16(pool, p)
        high = 1 << (8 * width - 1)
        if v & high:
            low = pool[p + width] if width == 1 else u16(pool, p + width)
            return ((v & (high - 1)) << (8 * width)) | low, p + 2 * width
        return v, p + width

    strings = []
    for i in range(count):
        p = start + u32(pool, 28 + 4 * i)
        n, p = length(p, 1 if utf8 else 2)
        if utf8:
            n, p = length(p, 1)
        strings.append(bytes(pool[p:p + n * (1 if utf8 else 2)]).decode('utf-8' if utf8 else 'utf-16le'))
    assert 'local.waze.diagnostics.SupportActivity' not in strings
    ns = strings.index('http://schemas.android.com/apk/res/android')
    extra, offsets, resources = bytearray(), [], {}

    def append(value, resource=0):
        idx = count + len(offsets)
        b = value.encode('utf-8' if utf8 else 'utf-16le')
        assert len(value) < 128 and len(b) < 128
        offsets.append(len(pool) - start + len(extra))
        extra.extend(bytes([len(value), len(b)]) + b + b'\0' if utf8
                     else struct.pack('<H', len(value)) + b + b'\0\0')
        resources[idx] = resource
        return idx

    name = append('name', 0x01010003)
    exported = append('exported', 0x01010010)
    permission = append('permission', 0x01010006)

    def attr(key, value, kind=3):
        return struct.pack('<IIIHBBI', ns, key, value if kind == 3 else 0xffffffff,
                           8, 0, kind, value)

    def node(tag, attrs):
        idx = append(tag)
        size = 36 + 20 * len(attrs)
        begin = struct.pack('<HHIII', 0x102, 16, size, 0, 0xffffffff)
        begin += struct.pack('<II6H', 0xffffffff, idx, 20, 20, len(attrs), 0, 0, 0)
        end = struct.pack('<HHIIIII', 0x103, 16, 24, 0, 0xffffffff, 0xffffffff, idx)
        return begin + b''.join(attrs) + end

    nodes = node('activity', [attr(name, append('local.waze.diagnostics.SupportActivity')),
                              attr(exported, 0, 0x12)])
    nodes += node('service', [attr(name, append('local.waze.diagnostics.DiagnosticsJob')),
                             attr(permission, append('android.permission.BIND_JOB_SERVICE')),
                             attr(exported, 0xffffffff, 0x12)])
    # Android 11+ package visibility: query exactly the gated HUD receiver package.
    query_child = node('package', [attr(name, append('com.byd.clusterdebug'))])
    queries_idx = next((i for i,c in enumerate(chunks)
                        if u16(c,0)==0x103 and strings[u32(c,20)]=='queries'), None)
    if queries_idx is not None:
        chunks.insert(queries_idx, query_child)
    else:
        query_tag=append('queries')
        begin=struct.pack('<HHIII',0x102,16,36,0,0xffffffff)+struct.pack('<II6H',0xffffffff,query_tag,20,20,0,0,0,0)
        end=struct.pack('<HHIIIII',0x103,16,24,0,0xffffffff,0xffffffff,query_tag)
        before_manifest_end=next(i for i,c in enumerate(chunks)
                                 if u16(c,0)==0x103 and strings[u32(c,20)]=='manifest')
        chunks.insert(before_manifest_end,begin+query_child+end)
    # Optional Usage Access for following the DiLink 5.1 cluster theme. Never granted by install.
    usage = node('uses-permission', [attr(name, append('android.permission.PACKAGE_USAGE_STATS'))])
    application_start = next(i for i, c in enumerate(chunks)
                             if u16(c, 0) == 0x102 and u32(c, 20) < len(strings) and strings[u32(c, 20)] == 'application')
    chunks.insert(application_start, usage)
    insertions = 0
    for i, chunk in enumerate(chunks):
        if u16(chunk, 0) == 0x103 and u32(chunk,20)<len(strings) and strings[u32(chunk, 20)] == 'application':
            chunks.insert(i, nodes)
            insertions += 1
            break
    assert insertions == 1
    new_pool = pool[:start] + struct.pack('<' + 'I' * len(offsets), *offsets) + pool[start:] + extra
    new_pool.extend(b'\0' * (-len(new_pool) % 4))
    struct.pack_into('<I', new_pool, 4, len(new_pool))
    struct.pack_into('<I', new_pool, 8, count + len(offsets))
    struct.pack_into('<I', new_pool, 16, flags & ~1)
    struct.pack_into('<I', new_pool, 20, start + 4 * len(offsets))
    chunks[chunks.index(pool)] = new_pool
    resource_map = next(c for c in chunks if u16(c, 0) == 0x180)
    new_map = resource_map + b'\0' * (8 + 4 * (count + len(offsets)) - len(resource_map))
    for idx, resource in resources.items():
        struct.pack_into('<I', new_map, 8 + idx * 4, resource)
    struct.pack_into('<I', new_map, 4, len(new_map))
    chunks[chunks.index(resource_map)] = new_map
    out = bytearray(data[:u16(data, 2)]) + b''.join(chunks)
    struct.pack_into('<I', out, 4, len(out))
    return bytes(out)
