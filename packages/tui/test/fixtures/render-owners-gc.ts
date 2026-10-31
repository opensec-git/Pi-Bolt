import assert from "node:assert/strict";
import { setImmediate } from "node:timers/promises";
import { Box } from "../../src/components/box.ts";
import { Text } from "../../src/components/text.ts";
import { Container } from "../../src/tui.ts";

assert.ok(global.gc, "run this fixture with --expose-gc");

function renderParents(child: Text, count: number): WeakRef<Box>[] {
	const refs: WeakRef<Box>[] = [];
	for (let i = 0; i < count; i++) {
		const parent = new Box(0, 0);
		parent.addChild(child);
		parent.render(20);
		refs.push(new WeakRef(parent));
	}
	return refs;
}

const singletonChild = new Text("singleton", 0, 0);
const sharedChild = new Text("shared", 0, 0);
const singleton = renderParents(singletonChild, 1);
const shared = renderParents(sharedChild, 100);
const liveBox = new Box(0, 0);
liveBox.addChild(sharedChild);
const liveParent = new Container();
liveParent.addChild(liveBox);
liveParent.render(20);

// WeakRef targets remain protected until the job that created/dereferenced them ends.
await setImmediate();
global.gc();
assert.equal(singleton.filter((ref) => ref.deref() !== undefined).length, 0, "singleton owner retained");
assert.equal(shared.filter((ref) => ref.deref() !== undefined).length, 0, "shared-child owners retained");

sharedChild.setText("updated");
assert.deepEqual(liveParent.render(20), ["updated".padEnd(20)]);
const replacement = new Box(0, 0);
replacement.addChild(singletonChild);
replacement.render(20);
singletonChild.setText("replacement");
assert.deepEqual(replacement.render(20), ["replacement".padEnd(20)]);
