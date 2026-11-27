"""Measure an edit call holding a multiline replacement inside an edits array, and check the exact serialized preview."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from records import b, run_record

cut = "\n… (cut for the phone)"


def edit(args):
    return {"role":"assistant","content":[{"type":"toolCall","name":"edit","id":"edit-1","arguments":args}]}


def case(mib):
    line = '    return {"path": "src/main.ts", "label": "東京", "escaped": "\\\\n"}\n'
    replacement = (line * (mib * 2**20 // len(line) + 1))[:mib * 2**20]
    edits = [{"oldText":"original","newText":replacement}, {"oldText":"next","newText":"last"}]
    payload = {"type":"message","message":edit({"path":"src/main.ts","edits":edits})}
    size = len(json.dumps(payload,ensure_ascii=False).encode())
    compact = json.dumps(edits,ensure_ascii=False,separators=(",",":"))
    expected = edit({"path":"src/main.ts","edits":compact[:131072] + cut})
    digest = hashlib.sha256(json.dumps(expected,ensure_ascii=False).encode()).hexdigest()
    return payload, expected, digest, size


parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--build",action="append",required=True)
args=parser.parse_args()
builds=b.parse_builds(args.build)
with b.fake_model() as port:
    for mib in (8, 32):
        record, expected, digest, size=case(mib)
        for pair in range(6):
            for build in builds if pair % 2 == 0 else reversed(builds):
                row=run_record(build,port,size,record,expected,digest)
                print(json.dumps(dict(build=build.name,pair=pair,source_mib=mib,**row)),flush=True)
