import { Skeleton, Table, Text, Tooltip } from '@mantine/core';
import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import type { RequestListItem, Role } from '@/shared/api';
import { formatInstant } from '../format';
import { StatusBadge } from '../StatusBadge';
import { BUSINESS_VIEWS, deliveryView } from '../statuses';
import { Deadline } from './Deadline';
import classes from './RequestList.module.css';

type Props = { items: RequestListItem[]; role: Role; timeZone: string };

function Head({ role }: { role: Role }) {
  return (
    <Table.Thead>
      <Table.Tr>
        <Table.Th>Номер</Table.Th>
        <Table.Th>Груз</Table.Th>
        {role === 'OPERATOR' && <Table.Th>Автор</Table.Th>}
        <Table.Th>Срок</Table.Th>
        <Table.Th>Результат</Table.Th>
        <Table.Th>Доставка</Table.Th>
        <Table.Th>Ответственный</Table.Th>
        <Table.Th>Создана</Table.Th>
      </Table.Tr>
    </Table.Thead>
  );
}

function TableFrame({ role, children }: { role: Role; children: ReactNode }) {
  return (
    <Table
      className={classes.table}
      stickyHeader
      stickyHeaderOffset="var(--app-shell-header-height)"
      highlightOnHover
      fz="md"
      horizontalSpacing="sm"
      verticalSpacing="xs"
      tabularNums
    >
      <Head role={role} />
      <Table.Tbody>{children}</Table.Tbody>
    </Table>
  );
}

/** The registry on a desktop (≥ md): one row per request, the whole row links to its card. */
export function RequestTable({ items, role, timeZone }: Props) {
  return (
    <TableFrame role={role}>
      {items.map((item) => (
        <Table.Tr key={item.id} className={classes.row}>
          <Table.Td>
            <Link to="/requests/$id" params={{ id: item.id }} className={classes.rowLink}>
              {item.number}
            </Link>
          </Table.Td>
          <Table.Td className={classes.cargoCell}>
            <Tooltip label={item.cargoDescription} openDelay={400} multiline maw="30rem">
              <Link to="/requests/$id" params={{ id: item.id }} className={classes.cargoLink} tabIndex={-1}>
                {item.cargoDescription}
              </Link>
            </Tooltip>
          </Table.Td>
          {role === 'OPERATOR' && <Table.Td>{item.owner.displayName}</Table.Td>}
          <Table.Td>
            <Deadline date={item.deadline} overdue={item.overdue} />
          </Table.Td>
          <Table.Td className={classes.badgeCell}>
            <StatusBadge view={BUSINESS_VIEWS[item.businessStatus]} />
          </Table.Td>
          <Table.Td className={classes.badgeCell}>
            <StatusBadge view={deliveryView(item.deliveryStatus, role)} />
          </Table.Td>
          <Table.Td>
            {item.assignee ? item.assignee.displayName : <Text span c="dimmed">—</Text>}
          </Table.Td>
          <Table.Td>
            <Text span size="md" c="dimmed">
              {formatInstant(item.createdAt, timeZone)}
            </Text>
          </Table.Td>
        </Table.Tr>
      ))}
    </TableFrame>
  );
}

/** First load: eight grey rows under the real header. */
export function RequestTableSkeleton({ role }: { role: Role }) {
  const columns = role === 'OPERATOR' ? 8 : 7;
  return (
    <TableFrame role={role}>
      {Array.from({ length: 8 }, (_, row) => (
        <Table.Tr key={row} className={classes.row} data-testid="skeleton-row">
          {Array.from({ length: columns }, (_, column) => (
            <Table.Td key={column}>
              <Skeleton h="0.75rem" radius="sm" />
            </Table.Td>
          ))}
        </Table.Tr>
      ))}
    </TableFrame>
  );
}
