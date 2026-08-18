#!/usr/bin/env python3
"""mkhex.py: convert an ELF into a readmemh word hex file for Chisel
loadMemoryFromFile (one 32-bit little-endian word per token, address starts at 0)."""
import subprocess, sys, os

def main():
    elf = sys.argv[1]
    out = sys.argv[2]
    binpath = elf + ".bin"
    subprocess.run(["riscv64-unknown-elf-objcopy", "-O", "binary", elf, binpath], check=True)
    data = open(binpath, "rb").read()
    while len(data) % 4:
        data += b"\x00"
    words = []
    for i in range(0, len(data), 4):
        words.append("%08x" % int.from_bytes(data[i:i + 4], "little"))
    with open(out, "w") as f:
        f.write(" ".join(words) + "\n")
    os.unlink(binpath)
    print(f"{out}: {len(words)} words")

if __name__ == "__main__":
    main()
