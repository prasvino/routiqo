/** Synthetic staging utility exploration for ADR 0055; never real user evidence. */
const VALUES = 4;
const WINDOWS = 10_000;

function random(seed) {
  let state = seed >>> 0;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x1_0000_0000;
  };
}

export function selectCommunityValue(values) {
  if (values.length < 12) return null;
  const counts = Array(VALUES).fill(0);
  for (const value of values) {
    if (!Number.isInteger(value) || value < 0 || value >= VALUES) {
      throw new Error('Invalid synthetic traffic value');
    }
    counts[value] += 1;
  }
  const eligible = counts.flatMap((count, value) =>
    count >= 10 && count * 5 >= values.length * 4 ? [value] : [],
  );
  return eligible.length === 1 ? eligible[0] : null;
}

function scenario(name, accountsPerWindow, truthfulProbability, maliciousAccounts, seed) {
  const next = random(seed);
  let shown = 0;
  let correct = 0;
  let wrong = 0;
  for (let window = 0; window < WINDOWS; window += 1) {
    const values = [];
    for (let account = 0; account < accountsPerWindow; account += 1) {
      if (account < maliciousAccounts) {
        values.push(1);
      } else {
        values.push(next() < truthfulProbability ? 0 : 1 + Math.floor(next() * 3));
      }
    }
    const result = selectCommunityValue(values);
    if (result !== null) {
      shown += 1;
      if (result === 0) correct += 1;
      else wrong += 1;
    }
  }
  return {
    name,
    accountsPerWindow,
    truthfulProbability,
    maliciousAccounts,
    windows: WINDOWS,
    visiblePercent: Number(((shown / WINDOWS) * 100).toFixed(2)),
    correctPercentOfVisible: shown ? Number(((correct / shown) * 100).toFixed(2)) : null,
    wrongPercentOfVisible: shown ? Number(((wrong / shown) * 100).toFixed(2)) : null,
  };
}

function repeatCommuterCap() {
  const accounts = 15;
  const windowsPerUtcDay = 24;
  const dailyCap = 12;
  const acceptedByWindow = Array.from({ length: windowsPerUtcDay }, (_, window) =>
    window < dailyCap ? accounts : 0,
  );
  return {
    accounts,
    windowsPerUtcDay,
    dailyCap,
    acceptedByWindow,
    windowsWithEnoughAccounts: acceptedByWindow.filter((count) => count >= 12).length,
  };
}

if (
  process.argv[1] &&
  import.meta.url === new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`).href
) {
  const output = {
    label: 'synthetic scenarios only; not measured Routiqo density, accuracy or latency',
    rule: 'at least 12 accounts, unique value with at least 10 and 80% support',
    seed: 0x5eed1234,
    scenarios: [
      scenario('low_density', 8, 0.9, 0, 0x5eed1234),
      scenario('medium_density', 12, 0.9, 0, 0x5eed1235),
      scenario('high_density_mixed', 25, 0.65, 0, 0x5eed1236),
      scenario('high_density_consistent', 25, 0.9, 0, 0x5eed1237),
      scenario('five_false_accounts', 25, 0.9, 5, 0x5eed1238),
      scenario('ten_false_accounts', 12, 1, 10, 0x5eed1239),
    ],
    repeatCommuterCap: repeatCommuterCap(),
  };
  process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
}
