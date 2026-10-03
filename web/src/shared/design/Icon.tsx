/** The prototype's icons: 20×20 grid, 1.6 stroke. */
const PATHS = {
  overview: <><rect x="2.5" y="2.5" width="6" height="6" rx="1.5" /><rect x="11.5" y="2.5" width="6" height="6" rx="1.5" /><rect x="2.5" y="11.5" width="6" height="6" rx="1.5" /><rect x="11.5" y="11.5" width="6" height="6" rx="1.5" /></>,
  plans: <><rect x="2.5" y="3.5" width="15" height="13" rx="2.5" /><path d="M2.5 8h15M7.5 8v8.5" /></>,
  subscribers: <><circle cx="8" cy="7" r="3" /><path d="M2.5 17c0-3 2.5-5 5.5-5s5.5 2 5.5 5" /><path d="M14 4.5a3 3 0 0 1 0 5.5" /></>,
  payments: <><rect x="2.5" y="4.5" width="15" height="11" rx="2" /><path d="M2.5 8.5h15M6 12.5h3" /></>,
  users: <><circle cx="7.2" cy="6.6" r="2.8" /><path d="M2.2 16.5c0-2.7 2.2-4.5 5-4.5s5 1.8 5 4.5" /><path d="M14.2 9.6l1.4 1.4 2.4-2.6" /></>,
  system: <><circle cx="10" cy="10" r="2.6" /><path d="M10 2.4v2.1M10 15.5v2.1M2.4 10h2.1M15.5 10h2.1M4.6 4.6l1.5 1.5M13.9 13.9l1.5 1.5M15.4 4.6l-1.5 1.5M6.1 13.9l-1.5 1.5" /></>,
  signOut: <><path d="M8 4.5H5a1.5 1.5 0 0 0-1.5 1.5v8A1.5 1.5 0 0 0 5 15.5h3" /><path d="M12 6.5L15.5 10 12 13.5M15.5 10H8" /></>,
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={1.6}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name]}
    </svg>
  );
}

export function Logo() {
  return (
    <svg width="24" height="24" viewBox="0 0 28 28" fill="none" aria-hidden="true">
      <circle cx="14" cy="14" r="11" stroke="var(--rc-action)" strokeWidth="2.4" strokeDasharray="52 17" strokeLinecap="round" transform="rotate(-90 14 14)" />
      <path d="M14 3.5 L18.4 7.2 L14 10.9" stroke="var(--rc-action)" strokeWidth="2.4" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
