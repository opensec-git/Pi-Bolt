import assert from "node:assert/strict";
import { setTimeout } from "node:timers/promises";
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

// Collects until no ref in `refs` is live, or gives up after about a second, and returns how many stay live.
// Each attempt first ends the current job: WeakRef targets stay protected until the job that created or
// dereferenced them ends. A single collection is not enough: a concurrent (background) optimizing compile
// started during renderParents() can still hold the parent of its trigger iteration when the first
// collection runs, most often on a loaded machine. Without concurrent compilation, one collection suffices.
async function liveAfterCollection(refs: WeakRef<Box>[]): Promise<number> {
	const live = () => refs.filter((ref) => ref.deref() !== undefined).length;
	for (let attempt = 0; attempt < 100; attempt++) {
		await setTimeout(attempt === 0 ? 0 : 10);
		global.gc?.();
		if (live() === 0) return 0;
	}
	return live();
}

assert.equal(await liveAfterCollection(singleton), 0, "singleton owner retained");
assert.equal(await liveAfterCollection(shared), 0, "shared-child owners retained");

sharedChild.setText("updated");
assert.deepEqual(liveParent.render(20), ["updated".padEnd(20)]);
const replacement = new Box(0, 0);
replacement.addChild(singletonChild);
replacement.render(20);
singletonChild.setText("replacement");
assert.deepEqual(replacement.render(20), ["replacement".padEnd(20)]);
