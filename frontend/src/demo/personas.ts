/** Demo personas with real seeded data.
 *
 * Phone numbers are derived by replicating java.util.Random(seed=20260930L)
 * through the SeedDatasetFactory call sequence (verified: only 3 random
 * call sites exist). Order numbers are deterministic (ORD-10001+ in creation
 * order). If the seed changes, these must be regenerated.
 */

export interface DemoOrder {
  number: string;
  items: string;
  total: string;
  status: 'Unfulfilled' | 'In transit' | 'Delivered' | 'Cancelled' | string;
  payment: string;
  note?: string;
}

export interface Persona {
  id: string;
  name: string;
  email: string;
  phone: string;
  accountStatus: string;
  scenario: string;
  tags: string[];
  orders: DemoOrder[];
  tryAsk: string[];
}

export interface Scenario {
  id: string;
  title: string;
  description: string;
  personaId: string;
  prompt: string;
}

export const SCENARIOS: Scenario[] = [
  {
    id: 'track',
    title: 'Track an order',
    description: 'Ask about the in-transit order with live shipment state.',
    personaId: 'fatima',
    prompt: 'Where is my order?',
  },
  {
    id: 'cancel',
    title: 'Cancel an order',
    description: 'Use the cancellable COD order — no refund needed.',
    personaId: 'bilal',
    prompt: 'I want to cancel my order ORD-10015',
  },
  {
    id: 'return',
    title: 'Return an item',
    description: 'Use the delivered order eligible for return.',
    personaId: 'hina',
    prompt: 'I want to return an item from ORD-10020',
  },
  {
    id: 'refund',
    title: 'Refund inquiry',
    description: 'Inspect an existing refund and payment state.',
    personaId: 'ayesha',
    prompt: 'Where is my refund?',
  },
  {
    id: 'payment',
    title: 'Payment issue',
    description: 'A failed payment and what happens next.',
    personaId: 'imran',
    prompt: 'My payment failed, what should I do?',
  },
  {
    id: 'urdu',
    title: 'Urdu / Roman Urdu',
    description: 'Switch languages mid-conversation.',
    personaId: 'ahmed',
    prompt: 'Mera order kahan hai?',
  },
  {
    id: 'interrupt',
    title: 'Voice interruption',
    description: 'Start a voice call, then talk over the agent.',
    personaId: 'sara',
    prompt: 'Tell me about my recent orders',
  },
];

export const PERSONAS: Persona[] = [
  {
    id: 'maria',
    name: 'Maria Khan',
    email: 'maria.khan@example.pk',
    phone: '+923002225319',
    accountStatus: 'Verified',
    scenario: 'Baseline customer with orders across states — cancellable, in-transit, and delivered.',
    tags: ['general', 'orders'],
    orders: [
      { number: 'ORD-10001', items: 'Wireless Mouse', total: 'PKR 4,700', status: 'Unfulfilled', payment: 'COD · Pending', note: 'Cancellable' },
      { number: 'ORD-10002', items: 'Bluetooth Headphones', total: 'PKR 12,000', status: 'Unfulfilled', payment: 'Card · Paid', note: 'Cancellable' },
      { number: 'ORD-10004', items: 'Mechanical Keyboard', total: 'PKR 15,300', status: 'In transit', payment: 'Card · Paid', note: 'TCS tracking' },
    ],
    tryAsk: ['Where is my order?', 'I want to cancel ORD-10001', 'What is your return policy?'],
  },
  {
    id: 'bilal',
    name: 'Bilal Sheikh',
    email: 'bilal.sheikh@example.pk',
    phone: '+923003328105',
    accountStatus: 'Verified',
    scenario: 'Cancellation matrix: orders in every cancellable state.',
    tags: ['cancellation', 'procedures'],
    orders: [
      { number: 'ORD-10015', items: 'USB-C Hub', total: 'PKR 7,700', status: 'Unfulfilled', payment: 'COD · Pending', note: 'Cancellable' },
      { number: 'ORD-10016', items: 'Electric Kettle', total: 'PKR 6,800', status: 'Unfulfilled', payment: 'Card · Paid', note: 'Cancellable' },
    ],
    tryAsk: ['I want to cancel my order', 'Which orders can I cancel?', 'Cancel ORD-10015'],
  },
  {
    id: 'fatima',
    name: 'Fatima Noor',
    email: 'fatima.noor@example.pk',
    phone: '+923004806335',
    accountStatus: 'Verified',
    scenario: 'Shipment tracking across all stages.',
    tags: ['tracking', 'shipments'],
    orders: [
      { number: 'ORD-10036', items: 'Smart Watch', total: 'PKR 25,150', status: 'In transit', payment: 'Card · Paid', note: 'TCS tracking' },
      { number: 'ORD-10037', items: 'Backpack', total: 'PKR 5,400', status: 'Delivered', payment: 'Card · Paid' },
    ],
    tryAsk: ['Where is my order?', 'Has my package shipped?', 'Track ORD-10036'],
  },
  {
    id: 'hina',
    name: 'Hina Tariq',
    email: 'hina.tariq@example.pk',
    phone: '+923008415628',
    accountStatus: 'Verified',
    scenario: 'Return window edges: eligible, expiring, and expired.',
    tags: ['returns', 'procedures'],
    orders: [
      { number: 'ORD-10020', items: 'Hair Dryer', total: 'PKR 7,200', status: 'Delivered', payment: 'Card · Paid', note: 'Return eligible' },
      { number: 'ORD-10021', items: 'LED Strip Lights', total: 'PKR 2,200', status: 'Delivered', payment: 'Card · Paid', note: 'Return window expiring' },
    ],
    tryAsk: ['I want to return an item', 'Can I still return ORD-10020?', 'What is the return window?'],
  },
  {
    id: 'ayesha',
    name: 'Ayesha Malik',
    email: 'ayesha.malik@example.pk',
    phone: '+923007349028',
    accountStatus: 'Verified',
    scenario: 'Refund paths: original method, bank transfer, partial refunds.',
    tags: ['refunds', 'payments'],
    orders: [
      { number: 'ORD-10026', items: 'Water Bottle (1L)', total: 'PKR 1,800', status: 'Delivered', payment: 'Card · Paid', note: 'Refund processed' },
      { number: 'ORD-10027', items: 'Ceramic Mug Set', total: 'PKR 2,550', status: 'Delivered', payment: 'Card · Paid', note: 'Refund pending' },
    ],
    tryAsk: ['Where is my refund?', 'How long do refunds take?', 'Check refund for ORD-10026'],
  },
  {
    id: 'imran',
    name: 'Imran Khalid',
    email: 'imran.khalid@example.pk',
    phone: '+923009613201',
    accountStatus: 'Verified',
    scenario: 'Payment states: successful, pending, failed.',
    tags: ['payments'],
    orders: [
      { number: 'ORD-10040', items: 'Bluetooth Speaker', total: 'PKR 9,800', status: 'Unfulfilled', payment: 'Card · Failed', note: 'Payment failed' },
      { number: 'ORD-10041', items: 'Notebook Pack (3)', total: 'PKR 1,200', status: 'Unfulfilled', payment: 'Card · Paid' },
    ],
    tryAsk: ['Did my payment go through?', 'My card was declined', 'Retry payment for ORD-10040'],
  },
  {
    id: 'ahmed',
    name: 'Ahmed Raza',
    email: 'ahmed.raza@example.pk',
    phone: '+923000554392',
    accountStatus: 'Verified',
    scenario: 'Baseline customer. Best for multilingual testing.',
    tags: ['multilingual', 'general'],
    orders: [
      { number: 'ORD-10006', items: 'Office Chair', total: 'PKR 32,000', status: 'Delivered', payment: 'Card · Paid' },
      { number: 'ORD-10008', items: 'Wall Clock', total: 'PKR 2,800', status: 'In transit', payment: 'Card · Paid' },
    ],
    tryAsk: ['Mera order kahan hai?', 'آپ اردو میں بات کر سکتے ہیں؟', 'Where is my order?'],
  },
  {
    id: 'sara',
    name: 'Sara Iqbal',
    email: 'sara.iqbal@example.pk',
    phone: '+923004895871',
    accountStatus: 'Verified',
    scenario: 'Baseline customer. Good for interruption and procedure flows.',
    tags: ['general', 'procedures'],
    orders: [
      { number: 'ORD-10011', items: 'USB-C Hub', total: 'PKR 7,500', status: 'Delivered', payment: 'Card · Paid' },
      { number: 'ORD-10012', items: 'Electric Kettle', total: 'PKR 6,800', status: 'Unfulfilled', payment: 'COD · Pending', note: 'Cancellable' },
    ],
    tryAsk: ['What are my recent orders?', 'I want to cancel ORD-10012', 'Tell me about returns'],
  },
];

/** Anonymous demo context: a sample order to reference, no identity attached. */
export const ANONYMOUS_CONTEXT = {
  note: 'No customer identity attached. The agent answers general questions and will ask for identification when a scenario needs it.',
  sampleOrder: 'ORD-10001',
  tryAsk: [
    'What is your return policy?',
    'How do I track my order?',
    'Do you support cash on delivery?',
    'What payment methods do you accept?',
  ],
};
