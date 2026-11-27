// Code of the program that crash-report.mjs runs in other realms.
export function compute(n) {
  let sum = 0;
  for (let i = 0; i < n; i++) sum += i % 7;
  return sum;
}
