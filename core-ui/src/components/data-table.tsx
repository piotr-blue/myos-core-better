// Adapted from myos-java's Developers DataTable, using the Core API row shapes.
import type { ReactNode, Ref } from "react";
export interface DataTableColumn<T> {
  key: string;
  label: ReactNode;
  render?: (row: T) => ReactNode;
  className?: string;
}
interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  data: T[];
  onRowClick?: (row: T) => void;
  className?: string;
  emptyMessage?: string;
  containerClassName?: string;
  getRowRef?: (row: T, index: number) => Ref<HTMLTableRowElement> | undefined;
}
export function DataTable<T>({
  columns,
  data,
  onRowClick,
  className = "",
  emptyMessage = "No data found.",
  containerClassName = "",
  getRowRef,
}: DataTableProps<T>) {
  return (
    <div className={`table-scroll ${containerClassName}`}>
      <table className={`data-table ${className}`}>
        <thead>
          <tr>
            {columns.map((col) => (
              <th scope="col" key={col.key} className={col.className}>
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length ? (
            data.map((row, index) => (
              <tr
                key={(row as { id?: string }).id ?? index}
                ref={getRowRef?.(row, index)}
                className={onRowClick ? "cursor-pointer" : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
              >
                {columns.map((col) => (
                  <td key={col.key} className={col.className}>
                    {col.render
                      ? col.render(row)
                      : (row as Record<string, ReactNode>)[col.key]}
                  </td>
                ))}
              </tr>
            ))
          ) : (
            <tr>
              <td colSpan={columns.length} className="empty-cell">
                {emptyMessage}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
