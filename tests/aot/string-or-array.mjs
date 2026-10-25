// Methods that strings and arrays both have (includes, indexOf, slice, at, concat, lastIndexOf), called on something that may be
// either: the string's method for a string, the array's for an array as the realm makes it, and a call like any other for
// everything else (an array of a class of the program, a typed array, an object of its own, a String object, a proxy).

function show(label, f) {
	try {
		const value = f();
		console.log(label, typeof value, Array.isArray(value) ? JSON.stringify(value) : String(value));
	} catch (error) {
		console.log(label, "throws", error.constructor.name, error.message);
	}
}

function includes(x, y) {
	return x.includes(y);
}
function includesFrom(x, y, from) {
	return x.includes(y, from);
}
function indexOf(x, y) {
	return x.indexOf(y);
}
function indexOfFrom(x, y, from) {
	return x.indexOf(y, from);
}
function lastIndexOf(x, y) {
	return x.lastIndexOf(y);
}
function slice(x, a, b) {
	return x.slice(a, b);
}
function slice1(x, a) {
	return x.slice(a);
}
function slice0(x) {
	return x.slice();
}
function at(x, i) {
	return x.at(i);
}
function concat(x, y) {
	return x.concat(y);
}
function ignored(x, y) {
	x.includes(y);
	x.indexOf(y);
	x.slice(1);
	return "done";
}

class MyArray extends Array {
	includes(y) {
		return `own includes ${y}`;
	}
	indexOf() {
		return -42;
	}
}
class PlainSubclass extends Array {}

const holey = [1, , 3];
const doubles = [1.5, 2.5, NaN, -0];
const objects = [{ a: 1 }, "b", null, undefined];
const big = Array.from({ length: 50 }, (_, i) => i * 3);
const sparse = [];
sparse[1000] = "far";
const withGetter = [1, 2, 3];
Object.defineProperty(withGetter, 1, {
	get() {
		console.log("getter ran");
		return 20;
	},
});
const own = {
	includes(y) {
		return `object includes ${y}`;
	},
	indexOf(y) {
		return `object indexOf ${y}`;
	},
	slice(a, b) {
		return `object slice ${a} ${b}`;
	},
	at(i) {
		return `object at ${i}`;
	},
	concat(y) {
		return `object concat ${y}`;
	},
	lastIndexOf(y) {
		return `object lastIndexOf ${y}`;
	},
};
const rope = ["ab", "cd", "ef"].join("") + String(Math.random() > 2 ? "" : "gh");
const wide = "ā€😀b€a";
const stringObject = new String("hello");
const proxy = new Proxy([5, 6, 7], {
	get(target, key, receiver) {
		if (typeof key === "string") console.log("proxy get", key);
		return Reflect.get(target, key, receiver);
	},
});
const typed = new Uint8Array([1, 2, 3, 4]);
const tostringer = {
	toString() {
		console.log("toString ran");
		return "b";
	},
};
const receivers = {
	string: "abcabc",
	empty: "",
	rope,
	wide,
	array: [1, 2, 3, "b", 2],
	emptyArray: [],
	holey,
	doubles,
	objects,
	big,
	sparse,
	withGetter,
	myArray: MyArray.from([1, 2, 3]),
	plainSubclass: PlainSubclass.from([1, 2, 3]),
	own,
	stringObject,
	proxy,
	typed,
	number: 42,
	nullValue: null,
	undefinedValue: undefined,
};
const needles = ["b", "", "c", 2, NaN, -0, 0, undefined, null, tostringer, objects[0], "abc", "😀", "€a", 1000];
const positions = [0, 1, -1, -100, 2.5, NaN, undefined, "1", 100, Infinity, -Infinity, -0];

for (let round = 0; round < 3; ++round) {
	for (const [name, x] of Object.entries(receivers)) {
		for (const y of needles) {
			const label = `${name} ${typeof y === "object" && y ? "obj" : String(y)}`;
			show(`includes ${label}`, () => includes(x, y));
			show(`indexOf ${label}`, () => indexOf(x, y));
			show(`lastIndexOf ${label}`, () => lastIndexOf(x, y));
			show(`concat ${label}`, () => concat(x, y));
		}
		for (const a of positions) {
			show(`slice1 ${name} ${String(a)}`, () => slice1(x, a));
			show(`at ${name} ${String(a)}`, () => at(x, a));
			show(`includesFrom ${name} ${String(a)}`, () => includesFrom(x, "b", a));
			show(`indexOfFrom ${name} ${String(a)}`, () => indexOfFrom(x, 2, a));
			for (const b of [0, 2, -1, undefined, 1.5]) show(`slice ${name} ${String(a)} ${String(b)}`, () => slice(x, a, b));
		}
		show(`slice0 ${name}`, () => slice0(x));
		show(`ignored ${name}`, () => ignored(x, "b"));
	}
}

// What a slice makes is a new array or string, not the receiver.
const original = [1, 2, 3];
const copy = slice0(original);
copy.push(4);
console.log("copy", JSON.stringify(original), JSON.stringify(copy), copy !== original);
// An array whose elements change while it is searched, through a getter.
const changing = [1, 2, 3];
Object.defineProperty(changing, 0, {
	get() {
		changing.length = 1;
		return 0;
	},
});
show("changing includes", () => includes(changing, 3));
show("changing indexOf", () => indexOf(changing, 3));
// Many calls of the same site, alternating receivers.
let total = 0;
for (let i = 0; i < 10000; ++i) {
	const x = i % 3 === 0 ? "abcdef" : i % 3 === 1 ? [1, 2, 3, i] : big;
	if (includes(x, i % 7 === 0 ? "c" : 3)) total += 1;
	total += indexOf(x, 3);
	total += slice(x, 1, 3).length;
}
console.log("total", total);
