// Deterministic content of the requests the load creates (docs/specs/load-test.md §4): the i-th request is always
// the same, only its deadline depends on the date of the server.

export type NewRequest = {
    cargoDescription: string;
    weightKg: number;
    deliveryAddress: string;
    deadline: string;
};

const CARGO = [
    'Паллеты с бытовой техникой', 'Строительные смеси', 'Оконные профили', 'Офисная мебель', 'Автозапчасти',
    'Продукты питания (сухие)', 'Садовый инвентарь', 'Упаковочные материалы', 'Электрокабель', 'Книги и канцелярия',
    'Лабораторное оборудование', 'Сантехника', 'Одежда и текстиль', 'Игрушки', 'Напитки в таре',
];

const CITIES = [
    'Москва', 'Санкт-Петербург', 'Казань', 'Нижний Новгород', 'Екатеринбург', 'Новосибирск', 'Самара', 'Краснодар',
    'Воронеж', 'Пермь', 'Ростов-на-Дону', 'Уфа',
];

const STREETS = [
    'ул. Ленина', 'ул. Мира', 'пр. Победы', 'ул. Советская', 'ул. Гагарина', 'ул. Садовая', 'пр. Космонавтов',
    'ул. Заводская', 'ул. Школьная', 'Складской проезд',
];

/** Marker the carrier rejects an address by (OutcomePolicy). */
const REJECT_ADDRESS_MARKER = 'тест-отказ';

/** Share of the requests the carrier rejects: by weight over 10 000 kg, and by the address marker. */
const OVERWEIGHT_SHARE = 0.05;
const REJECTED_ADDRESS_SHARE = 0.05;

/** mulberry32: a small seeded generator, values in [0, 1). */
function mulberry32(seed: number): () => number {
    let state = seed >>> 0;
    return () => {
        state = (state + 0x6d2b79f5) >>> 0;
        let t = state;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

function pick<T>(items: readonly T[], random: () => number): T {
    return items[Math.floor(random() * items.length)] as T;
}

/**
 * The deadline is the UTC date of the server + 1 + (i mod 60) days. k6 has no time zones, but a UTC date plus a day
 * is not before "today" in any zone (offsets are −12…+14 h), so the rule "deadline is not before today" holds.
 */
export function deadline(serverTime: string, i: number): string {
    const now = new Date(serverTime);
    const day = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1 + (i % 60));
    return new Date(day).toISOString().slice(0, 10);
}

export function requestContent(i: number, serverTime: string): NewRequest {
    const random = mulberry32(Math.imul(i + 1, 0x9e3779b1));
    const overweight = random() < OVERWEIGHT_SHARE;
    const rejectedAddress = random() < REJECTED_ADDRESS_SHARE;

    // 10 000.1…20 000 kg is over the carrier's limit; otherwise 0.1…10 000 kg with one decimal place.
    const weightKg = overweight
        ? Math.round((10000.1 + random() * 9999.9) * 10) / 10
        : Math.round((0.1 + random() * 9999.9) * 10) / 10;

    const street = `${pick(STREETS, random)}, ${1 + Math.floor(random() * 120)}`;
    const address = `${pick(CITIES, random)}, ${street}${rejectedAddress ? ` (${REJECT_ADDRESS_MARKER})` : ''}`;

    return {
        cargoDescription: `${pick(CARGO, random)}, партия ${i + 1}`,
        weightKg,
        deliveryAddress: address,
        deadline: deadline(serverTime, i),
    };
}

/** Idempotency-Key of the i-th seed request. */
export function seedKey(i: number): string {
    return `load-seed-${i}`;
}

/** Idempotency-Key of a request the run creates (the contract allows 100 characters). */
export function runKey(runId: string, vu: number, iteration: number): string {
    return `load-${runId}-${vu}-${iteration}`;
}
