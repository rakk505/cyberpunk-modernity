"""
Minimal, dependency-free Anvil (.mca) + NBT reader/writer for Minecraft 1.21.x
world saves. Only implements what the citygen tooling needs:

  * read region files -> chunk NBT
  * decode block_states (palette + packed long array) for each 16x16x16 section
  * random block lookup at (x, y, z) world coordinates
  * write a structure .nbt (gzip TAG_Compound) in the vanilla structure format

This is intentionally small and readable rather than a full NBT library.
"""
from __future__ import annotations

import gzip
import struct
import zlib
from dataclasses import dataclass, field
from typing import Any

# ---------------------------------------------------------------------------
# NBT tag ids
# ---------------------------------------------------------------------------
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class _Reader:
    def __init__(self, data: bytes):
        self.d = data
        self.i = 0

    def u1(self) -> int:
        v = self.d[self.i]
        self.i += 1
        return v

    def i1(self) -> int:
        v = struct.unpack_from(">b", self.d, self.i)[0]
        self.i += 1
        return v

    def i2(self) -> int:
        v = struct.unpack_from(">h", self.d, self.i)[0]
        self.i += 2
        return v

    def u2(self) -> int:
        v = struct.unpack_from(">H", self.d, self.i)[0]
        self.i += 2
        return v

    def i4(self) -> int:
        v = struct.unpack_from(">i", self.d, self.i)[0]
        self.i += 4
        return v

    def i8(self) -> int:
        v = struct.unpack_from(">q", self.d, self.i)[0]
        self.i += 8
        return v

    def f4(self) -> float:
        v = struct.unpack_from(">f", self.d, self.i)[0]
        self.i += 4
        return v

    def f8(self) -> float:
        v = struct.unpack_from(">d", self.d, self.i)[0]
        self.i += 8
        return v

    def string(self) -> str:
        n = self.u2()
        s = self.d[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return s

    def payload(self, tag: int) -> Any:
        if tag == TAG_BYTE:
            return self.i1()
        if tag == TAG_SHORT:
            return self.i2()
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            n = self.i4()
            v = self.d[self.i:self.i + n]
            self.i += n
            return bytearray(v)
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item = self.u1()
            n = self.i4()
            return [self.payload(item) for _ in range(n)]
        if tag == TAG_COMPOUND:
            out: dict[str, Any] = {}
            while True:
                t = self.u1()
                if t == TAG_END:
                    break
                name = self.string()
                out[name] = self.payload(t)
            return out
        if tag == TAG_INT_ARRAY:
            n = self.i4()
            v = list(struct.unpack_from(">%di" % n, self.d, self.i))
            self.i += 4 * n
            return v
        if tag == TAG_LONG_ARRAY:
            n = self.i4()
            v = list(struct.unpack_from(">%dq" % n, self.d, self.i))
            self.i += 8 * n
            return v
        raise ValueError(f"Unknown tag {tag}")


def parse_nbt(data: bytes) -> dict:
    """Parse an uncompressed NBT byte string; returns the root compound value."""
    r = _Reader(data)
    root_tag = r.u1()
    assert root_tag == TAG_COMPOUND, root_tag
    r.string()  # root name
    return r.payload(TAG_COMPOUND)


# ---------------------------------------------------------------------------
# Region file
# ---------------------------------------------------------------------------
class Region:
    def __init__(self, path: str):
        with open(path, "rb") as f:
            self.raw = f.read()

    def chunk_nbt(self, cx_local: int, cz_local: int) -> dict | None:
        """cx_local/cz_local are 0..31 within the region."""
        idx = 4 * (cx_local + cz_local * 32)
        loc = struct.unpack_from(">I", self.raw, idx)[0]
        offset = (loc >> 8) * 4096
        count = (loc & 0xFF) * 4096
        if offset == 0 or count == 0:
            return None
        length = struct.unpack_from(">I", self.raw, offset)[0]
        comp = self.raw[offset + 4]
        payload = self.raw[offset + 5: offset + 4 + length]
        if comp == 1:
            data = gzip.decompress(payload)
        elif comp == 2:
            data = zlib.decompress(payload)
        else:
            raise ValueError(f"Unsupported compression {comp}")
        return parse_nbt(data)


# ---------------------------------------------------------------------------
# Chunk block access (1.18+ format: sections[].block_states.{palette,data})
# ---------------------------------------------------------------------------
def _bits_per_index(palette_len: int) -> int:
    if palette_len <= 1:
        return 0
    b = max(4, (palette_len - 1).bit_length())
    return b


def decode_section_blocks(section: dict):
    """Return (palette:list[dict], indices:list[int] length 4096) or None if empty.
    Each palette entry is the raw dict: {"Name": str, "Properties": {..}?}."""
    bs = section.get("block_states")
    if bs is None:
        return None
    palette = bs.get("palette")
    if palette is None:
        return None
    names = list(palette)
    data = bs.get("data")
    if data is None:
        # single-block section
        return names, [0] * 4096
    bpi = _bits_per_index(len(names))
    if bpi == 0:
        return names, [0] * 4096
    per_long = 64 // bpi
    mask = (1 << bpi) - 1
    indices = []
    for lng in data:
        u = lng & 0xFFFFFFFFFFFFFFFF
        for k in range(per_long):
            if len(indices) >= 4096:
                break
            indices.append((u >> (k * bpi)) & mask)
    indices = indices[:4096]
    while len(indices) < 4096:
        indices.append(0)
    return names, indices


@dataclass
class LoadedChunk:
    cx: int
    cz: int
    y_min: int
    # sections keyed by section_y -> (palette, indices)
    sections: dict = field(default_factory=dict)

    def block(self, x: int, y: int, z: int) -> str:
        """Return the block NAME at (localx, absy, localz)."""
        entry = self.block_entry(x, y, z)
        return entry["Name"] if entry is not None else "minecraft:air"

    def block_entry(self, x: int, y: int, z: int):
        """Return the full palette dict (Name + optional Properties) or None for air."""
        sy = y >> 4
        sec = self.sections.get(sy)
        if sec is None:
            return None
        palette, indices = sec
        ly = y & 15
        i = (ly * 16 + (z & 15)) * 16 + (x & 15)
        return palette[indices[i]]


def load_chunk(nbt: dict) -> LoadedChunk | None:
    secs = nbt.get("sections")
    if secs is None:
        return None
    xpos = nbt.get("xPos", 0)
    zpos = nbt.get("zPos", 0)
    lc = LoadedChunk(cx=xpos, cz=zpos, y_min=nbt.get("yPos", -4) * 16)
    for s in secs:
        decoded = decode_section_blocks(s)
        if decoded is None:
            continue
        lc.sections[s["Y"]] = decoded
    return lc


# ---------------------------------------------------------------------------
# NBT writer (for vanilla structure .nbt files)
# ---------------------------------------------------------------------------
class _Writer:
    def __init__(self):
        self.parts: list[bytes] = []

    def raw(self, b: bytes):
        self.parts.append(b)

    def u1(self, v):
        self.parts.append(struct.pack(">B", v))

    def i2(self, v):
        self.parts.append(struct.pack(">h", v))

    def i4(self, v):
        self.parts.append(struct.pack(">i", v))

    def string(self, s: str):
        b = s.encode("utf-8")
        self.parts.append(struct.pack(">H", len(b)))
        self.parts.append(b)

    def getvalue(self) -> bytes:
        return b"".join(self.parts)


def _write_payload(w: _Writer, tag: int, value: Any):
    if tag == TAG_BYTE:
        w.parts.append(struct.pack(">b", value))
    elif tag == TAG_SHORT:
        w.parts.append(struct.pack(">h", value))
    elif tag == TAG_INT:
        w.parts.append(struct.pack(">i", value))
    elif tag == TAG_LONG:
        w.parts.append(struct.pack(">q", value))
    elif tag == TAG_FLOAT:
        w.parts.append(struct.pack(">f", value))
    elif tag == TAG_DOUBLE:
        w.parts.append(struct.pack(">d", value))
    elif tag == TAG_STRING:
        w.string(value)
    elif tag == TAG_LIST:
        # value is a tuple (item_tag, [payloads])
        item_tag, items = value
        w.u1(item_tag)
        w.i4(len(items))
        for it in items:
            _write_payload(w, item_tag, it)
    elif tag == TAG_COMPOUND:
        # value is dict name -> (tag, payload)
        for name, (t, v) in value.items():
            w.u1(t)
            w.string(name)
            _write_payload(w, t, v)
        w.u1(TAG_END)
    elif tag == TAG_INT_ARRAY:
        w.i4(len(value))
        for v in value:
            w.i4(v)
    else:
        raise ValueError(f"writer unsupported tag {tag}")


def write_nbt_gzip(root: dict, path: str, root_name: str = ""):
    """root is a compound value in the (tag, payload) dict form."""
    w = _Writer()
    w.u1(TAG_COMPOUND)
    w.string(root_name)
    _write_payload(w, TAG_COMPOUND, root)
    with gzip.open(path, "wb") as f:
        f.write(w.getvalue())


def C(d: dict):
    return (TAG_COMPOUND, d)


def LIST(item_tag: int, items: list):
    return (TAG_LIST, (item_tag, items))


def INT(v):
    return (TAG_INT, v)


def STR(v):
    return (TAG_STRING, v)


def _palette_entry_to_nbt(entry: dict):
    """entry: {"Name": str, "Properties": {k: str}?} -> bare compound payload (dict)."""
    comp = {"Name": STR(entry["Name"])}
    props = entry.get("Properties")
    if props:
        comp["Properties"] = C({k: STR(str(v)) for k, v in props.items()})
    return comp


def write_structure(path: str, size_xyz, palette_entries, blocks, data_version=4671):
    """
    Write a vanilla structure .nbt.
      size_xyz: (sx, sy, sz)
      palette_entries: list of dicts {"Name":..., "Properties": {...}?}
      blocks: list of (x, y, z, palette_index) for NON-AIR blocks only
    """
    sx, sy, sz = size_xyz
    palette_list = [_palette_entry_to_nbt(e) for e in palette_entries]
    block_list = []
    for (x, y, z, state) in blocks:
        block_list.append({
            "state": INT(state),
            "pos": LIST(TAG_INT, [x, y, z]),
        })
    root = {
        "size": LIST(TAG_INT, [sx, sy, sz]),
        "entities": LIST(TAG_COMPOUND, []),
        "blocks": LIST(TAG_COMPOUND, block_list),
        "palette": LIST(TAG_COMPOUND, palette_list),
        "DataVersion": INT(data_version),
    }
    write_nbt_gzip(root, path, root_name="")
