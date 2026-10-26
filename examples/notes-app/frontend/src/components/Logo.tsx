// The Jolt icon, on a red-to-orange tile. These are the same shapes as the window icon (icon.ico). To change the icon,
// replace icon.ico and the shapes below (see "Change the app icon" in the main README).
export function Logo() {
  return (
    <svg
      viewBox="0 0 256 256"
      aria-hidden="true"
      className="size-9 flex-none overflow-visible"
      style={{ filter: 'drop-shadow(0 6px 10px color-mix(in srgb, var(--accent-a) 38%, transparent))' }}
    >
      <defs>
        <linearGradient id="logo-tile" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#F2381F" />
          <stop offset="1" stopColor="#FF8A0F" />
        </linearGradient>
      </defs>
      <rect x="8" y="8" width="240" height="240" rx="56" fill="url(#logo-tile)" />
      <path
        d="M105.0 62.0 H197.0 M180.0 62.0 L180.0 100.0 L152.0 126.0 L152.0 156.0 A38.0 38.0 0 0 1 76.0 156.0"
        fill="none"
        stroke="#fff"
        strokeWidth="34"
        strokeLinejoin="miter"
        strokeMiterlimit="4"
      />
    </svg>
  );
}
