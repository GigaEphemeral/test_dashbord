/**
 * Stub of "k6/metrics": Rate/Counter record every add() value.
 * `added` keeps the value-only shape the original tests assert on; `samples`
 * additionally records the tag objects (additive, for tag assertions).
 */
class Metric {
  constructor(name) {
    this.name = name;
    this.added = [];
    this.samples = [];
  }

  add(value, tags) {
    this.added.push(value);
    this.samples.push({ value, tags: tags === undefined ? null : tags });
  }

  reset() {
    this.added.length = 0;
    this.samples.length = 0;
  }
}

export class Rate extends Metric {}

export class Counter extends Metric {}

export class Gauge extends Metric {}
