const dateTime = new Intl.DateTimeFormat(undefined, {
  month: 'short', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
});
export const timeZone = dateTime.resolvedOptions().timeZone;
export function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Unavailable' : dateTime.format(date);
}
export function formatCount(value: number): string {
  return value.toLocaleString();
}
