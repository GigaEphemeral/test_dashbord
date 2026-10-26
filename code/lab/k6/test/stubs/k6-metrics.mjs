/**
 * Stub of "k6/metrics": Rate/Counter record every add() value.
 */
class Metric {
  constructor(name) {
    this.name = name;
    this.added = [];
  }

  add(value) {
    this.added.push(value);
  }

  reset() {
    this.added.length = 0;
  }
}

export class Rate extends Metric {}

export class Counter extends Metric {}
