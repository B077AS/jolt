import { useEffect, useState } from 'react';
import { api, Preference, Scheme } from './api';

/**
 * The colours Java paints the native title bar and web view background with. They must equal --canvas and --ink in
 * styles.css, so the title bar blends into the page.
 */
export const WINDOW_COLORS: Record<Scheme, { background: string; text: string }> = {
  light: { background: '#fbf7f4', text: '#221b18' },
  dark: { background: '#15110f', text: '#f4ede8' },
};

/**
 * The user's theme choice (system, light or dark). It is remembered by Java, applied to the page through the
 * data-theme attribute, and pushed to the native window so the title bar matches.
 */
export function useTheme(onError: (message: string) => void) {
  const darkQuery = '(prefers-color-scheme: dark)';
  const [preference, setPreference] = useState<Preference>('system');
  const [loaded, setLoaded] = useState(false);
  const [systemDark, setSystemDark] = useState(() => window.matchMedia(darkQuery).matches);

  // what Java remembered from last time
  useEffect(() => {
    api.getPreference().then((r) => setPreference(r.preference)).catch((e) => onError(String(e.message ?? e))).finally(() => setLoaded(true));
  }, [onError]);

  // follow the operating system while the choice is "system"
  useEffect(() => {
    const query = window.matchMedia(darkQuery);
    const listener = (event: MediaQueryListEvent) => setSystemDark(event.matches);
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }, []);

  const scheme: Scheme = preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;

  useEffect(() => {
    document.documentElement.dataset.theme = scheme;
    if (!loaded) return; // do not overwrite what Java remembered before we have read it
    const { background, text } = WINDOW_COLORS[scheme];
    api.setTheme({ preference, scheme, background, text }).catch((e) => onError(String(e.message ?? e)));
  }, [preference, scheme, loaded, onError]);

  return { preference, setPreference, scheme };
}
