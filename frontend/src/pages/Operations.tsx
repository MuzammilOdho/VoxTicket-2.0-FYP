import { useState } from 'react';
import { NavLink, useParams } from 'react-router-dom';
import { useOperations } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime } from '../components/KpiCard';

const TABS = [
  { kind: 'orders', label: 'Orders' },
  { kind: 'returns', label: 'Returns' },
  { kind: 'refunds', label: 'Refunds' },
  { kind: 'claims', label: 'Claims' },
  { kind: 'verification', label: 'Verification' },
  { kind: 'challenges', label: 'Challenges' },
  { kind: 'escalations', label: 'Escalations' },
];


/** Explicit columns per kind (Stage 5). Previously the table derived its
 * columns from the first row's keys, so a field missing from that row
 * silently dropped a column and ordering followed the JSON. These lists
 * come from the backend DTOs (OperationDtos.java: OrderDto, ReturnDto,
 * RefundDto, ClaimDto, VerificationChallengeDto, EscalationDto); a value
 * missing from a row renders "n/a" via getCell, never "[object Object]".
 * verification and challenges share one DTO and one column set. */
function operationColumns(kind: string) {
  const status = (r: Record<string, unknown>) => {
    const v = r.status;
    return typeof v === 'string' ? <StatusBadge value={v} /> : <span className="text-ink-mute">—</span>;
  };
  const instant = (key: string) => (r: Record<string, unknown>) => {
    const v = r[key];
    return typeof v === 'string' ? <span className="whitespace-nowrap">{fmtTime(v)}</span> : <span className="text-ink-mute">—</span>;
  };
  const bool = (key: string) => (r: Record<string, unknown>) => {
    const v = r[key];
    return typeof v === 'boolean' ? (v ? 'yes' : 'no') : <span className="text-ink-mute">—</span>;
  };
  const money = (r: Record<string, unknown>) => {
    const amount = r.totalAmount ?? r.amount;
    const currency = typeof r.currency === 'string' ? r.currency : '';
    return amount === null || amount === undefined ? <span className="text-ink-mute">—</span> : `${currency} ${amount}`.trim();
  };
  const mono = (key: string) => (r: Record<string, unknown>) => {
    const v = r[key];
    return typeof v === 'string' ? <span className="font-mono text-xs">{v}</span> : <span className="text-ink-mute">—</span>;
  };
  switch (kind) {
    case 'orders':
      return [
        { key: 'orderNumber', header: 'Order', render: mono('orderNumber') },
        { key: 'status', header: 'Status', render: status },
        { key: 'fulfillmentStatus', header: 'Fulfilment' },
        { key: 'totalAmount', header: 'Total', render: money },
        { key: 'itemCount', header: 'Items' },
        { key: 'placedAt', header: 'Placed', render: instant('placedAt') },
      ];
    case 'returns':
      return [
        { key: 'returnNumber', header: 'Return', render: mono('returnNumber') },
        { key: 'orderNumber', header: 'Order', render: mono('orderNumber') },
        { key: 'status', header: 'Status', render: status },
        { key: 'reason', header: 'Reason' },
        { key: 'requestedAt', header: 'Requested', render: instant('requestedAt') },
        { key: 'completedAt', header: 'Completed', render: instant('completedAt') },
      ];
    case 'refunds':
      return [
        { key: 'refundNumber', header: 'Refund', render: mono('refundNumber') },
        { key: 'orderNumber', header: 'Order', render: mono('orderNumber') },
        { key: 'amount', header: 'Amount', render: money },
        { key: 'status', header: 'Status', render: status },
        { key: 'reason', header: 'Reason' },
        { key: 'initiatedAt', header: 'Initiated', render: instant('initiatedAt') },
        { key: 'completedAt', header: 'Completed', render: instant('completedAt') },
      ];
    case 'claims':
      return [
        { key: 'claimNumber', header: 'Claim', render: mono('claimNumber') },
        { key: 'orderNumber', header: 'Order', render: mono('orderNumber') },
        { key: 'reason', header: 'Reason' },
        { key: 'requestedResolution', header: 'Requested resolution' },
        { key: 'status', header: 'Status', render: status },
        { key: 'createdAt', header: 'Created', render: instant('createdAt') },
      ];
    case 'verification':
    case 'challenges':
      return [
        { key: 'id', header: 'ID', render: mono('id') },
        { key: 'sessionId', header: 'Session', render: mono('sessionId') },
        { key: 'purpose', header: 'Purpose' },
        { key: 'deliveryChannel', header: 'Channel' },
        { key: 'maskedDestination', header: 'Destination', render: mono('maskedDestination') },
        { key: 'attempts', header: 'Attempts' },
        { key: 'verified', header: 'Verified', render: bool('verified') },
        { key: 'consumed', header: 'Consumed', render: bool('consumed') },
        { key: 'expired', header: 'Expired', render: bool('expired') },
        { key: 'createdAt', header: 'Created', render: instant('createdAt') },
        { key: 'expiresAt', header: 'Expires', render: instant('expiresAt') },
      ];
    case 'escalations':
      return [
        { key: 'ticketNumber', header: 'Ticket', render: mono('ticketNumber') },
        { key: 'category', header: 'Category' },
        { key: 'priority', header: 'Priority' },
        { key: 'status', header: 'Status', render: status },
        { key: 'summary', header: 'Summary' },
        { key: 'createdAt', header: 'Created', render: instant('createdAt') },
        { key: 'resolvedAt', header: 'Resolved', render: instant('resolvedAt') },
      ];
    default:
      return [{ key: 'id', header: 'ID' }];
  }
}

export function Operations() {
  const { kind = 'orders' } = useParams();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [q, setQD] = useState('');
  const [appliedQ, setAppliedQ] = useState<string | undefined>(undefined);
  const data = useOperations(kind, { page, size, q: appliedQ });
  const tab = TABS.find((t) => t.kind === kind);

  return (
    <div>
      {/* The tab IS the page title now — "Operations · Orders" repeated
          the section name without adding information. */}
      <PageHeader title={tab?.label ?? kind} sub="Read-only operational records. Business actions are performed by the conversation flows, not here." />
      <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Operations">
        {TABS.map((t) => (
          <NavLink
            key={t.kind}
            to={`/admin/operations/${t.kind}`}
            className={({ isActive }) =>
              `rounded-full px-4 py-2 text-[14px] font-medium transition-colors ${isActive ? 'bg-brand text-white' : 'border border-line text-ink-mute hover:border-stone hover:text-ink'}`
            }
          >
            {t.label}
          </NavLink>
        ))}
      </div>
      <FilterBar
        chips={appliedQ ? [{ label: `Search: ${appliedQ}`, onRemove: () => { setAppliedQ(undefined); setQD(''); setPage(0); } }] : []}
      >
        <TextInput label="Search" placeholder="id / number / customer" value={q} onChange={(e) => setQD(e.target.value)} />
        <Button onClick={() => { setPage(0); setAppliedQ(q || undefined); }}>Apply</Button>
      </FilterBar>
      <QueryState query={data} emptyTitle={`No ${tab?.label.toLowerCase() ?? 'records'}`} skeleton>
        {(p) =>
          p.content.length === 0 ? (
            <EmptyState title={`No ${tab?.label.toLowerCase() ?? 'records'}`} />
          ) : (
            <DataTable
              columns={operationColumns(kind)}
              rows={p.content}
              rowKey={(_, i) => `${kind}-${i}`}
              page={p.page}
              size={p.size}
              totalPages={p.totalPages}
              totalElements={p.totalElements}
              onPage={setPage}
              onSizeChange={(n) => { setSize(n); setPage(0); }}
            />
          )
        }
      </QueryState>
    </div>
  );
}
