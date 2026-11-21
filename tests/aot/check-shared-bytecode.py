"""Check that the worker fixture's ELF or Mach-O stores its linked bytecode and strings once."""
import mmap
import struct
import sys


def check(image):
    # The executable section starts with a length, and ends with StandaloneModuleGraph::Offsets and the trailer.
    trailer = b"\n---- Bun! ----\n"
    end = image.rfind(trailer) - struct.calcsize("<Q6I")
    if end < 0:
        raise ValueError("missing Bun standalone graph")
    total, modules, length, _, argv, _, flags = struct.unpack_from("<Q6I", image, end)
    graph = end - total
    if graph < 8 or struct.unpack_from("<Q", image, graph - 8)[0] != total + 32 + len(trailer):
        raise ValueError("invalid standalone graph length")

    # Match the optional records after the 52-byte CompiledModuleGraphFile entries, in flags order.
    required = (1 << 7) | (1 << 13) | (1 << 14)
    if flags & required != required or length % 52:
        raise ValueError("missing linked bytecode, strings or static heap")
    at = graph + modules + length
    entries = [struct.unpack_from("<II", image, module + 24) for module in range(graph + modules, at, 52)]
    if flags & (1 << 5):  # source hashes
        at += 4 * (length // 52)
    if flags & (1 << 6):  # builtin module id and bytecode pointer
        count, = struct.unpack_from("<I", image, at)
        entries.extend(struct.unpack_from("<II", image, at + 8 + 12 * i) for i in range(count))
        at += 4 + 12 * count
    strings = struct.unpack_from("<II", image, at)
    at += 8
    if flags & (1 << 8):  # startup module count
        at += 4
    if flags & (1 << 9):  # module-info string table
        at += 8
    if flags & (1 << 11):  # prelinked module graph and file indices
        at += 12 + 4 * struct.unpack_from("<I", image, at + 8)[0]
    if flags & (1 << 12):  # runtime options
        at += 8
    payload = struct.unpack_from("<II", image, at)
    # Every nonempty module or builtin entry is a suffix of the shared payload, including the worker's.
    start, size = payload
    for position, length in entries:
        if length and (position < start or position + length != start + size):
            raise ValueError("a module or builtin bytecode entry points outside the shared payload")
    at += 8 + 6 * 4  # linked payload pointer and six region ends
    heap_offset, heap_size = struct.unpack_from("<II", image, at)
    heap = graph + heap_offset
    if at + 8 != graph + argv or heap + heap_size > end or image[heap:heap + 8] != b"BTHEAP08":
        raise ValueError("invalid static heap record")

    # This fixture has a full Data arena. Map each address in StaticHeap::Header back to file bytes.
    header = struct.unpack_from("<44Q", image, heap)
    if header[14] or header[43] or header[3] + header[8] > header[2] or header[2] > heap_size:
        raise ValueError("expected an unabridged Data arena in the executable")
    for name, pointer, field in (("string table", strings, 15), ("linked bytecode", payload, 22)):
        position, size = pointer
        relative = header[field] - header[42]  # regionBase
        start = graph + position
        if not size or size != header[field + 1] or not 0 <= relative <= header[8] - size:
            raise ValueError(f"{name}: invalid arena bounds")
        if start != heap + header[3] + relative or start % 128:
            raise ValueError(f"{name}: graph and static heap do not reference the same aligned file bytes")
        # Equal pointers alone would also pass if the writer left an unreferenced copy in the file.
        with memoryview(image)[start:start + size] as data:
            if image.find(data) != start or image.find(data, start + 1) != -1:
                raise ValueError(f"{name}: duplicate payload in executable")
        print(f"PASS {name}: one shared range of {size} bytes")


if __name__ == "__main__":
    try:
        with open(sys.argv[1], "rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as image:
            check(image)
    except (OSError, struct.error, ValueError) as exc:
        sys.exit(f"FAIL shared bytecode layout: {exc}")
