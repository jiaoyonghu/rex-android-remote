#!/usr/bin/env python3
"""Offline REx 32-bit NEC codec; never accesses robot hardware or ROS."""
import json
from pathlib import Path
codes=json.loads((Path(__file__).with_name('ir_codes.json')).read_text())
addr=int(codes['address_hex'],16)
for name, value in codes['commands'].items():
    cmd=int(value,16)
    word=(addr&0xff)|((addr>>8)<<8)|(cmd<<16)|((cmd^0xff)<<24)
    decoded=bytes((word>>(8*i))&255 for i in range(4))
    assert decoded[0]==0x4c and decoded[1]==0x3b and decoded[2]==cmd and decoded[3]==cmd^0xff
    print(f'{name:14} command=0x{cmd:02X} bytes={decoded.hex(" ")} uint32=0x{word:08X}')
