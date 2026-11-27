// What workers-many.mjs's workers do, and the main thread does to check them.
export function work(seed) {
  const seen = new Map();
  let digest = seed;
  for (let i = 0; i < 2000; i++) {
    const key = `k${(i * 31 + seed) % 97}`;
    seen.set(key, (seen.get(key) ?? 0) + 1);
    if (/^k(\d)/.test(key)) digest = (digest * 33 + i) % 1000003;
  }
  return `${digest}:${seen.size}`;
}
