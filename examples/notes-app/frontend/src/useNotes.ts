import { useCallback, useEffect, useState } from 'react';
import { api, AppInfo, Note } from './api';

/** The notes, kept in sync with the Java side, plus the last error to show the user. */
export function useNotes() {
  const [notes, setNotes] = useState<Note[] | null>(null);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [error, setError] = useState('');

  const fail = useCallback((e: unknown) => setError(e instanceof Error ? e.message : String(e)), []);

  /** Run an action; show its failure (for example "A note cannot be empty") instead of losing it. True if it succeeded. */
  const attempt = useCallback(async (action: () => Promise<void>): Promise<boolean> => {
    setError('');
    try {
      await action();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  }, [fail]);

  const refresh = useCallback(async () => {
    const list = await api.list();
    setNotes([...list].sort((a, b) => b.createdAt - a.createdAt));
  }, []);

  useEffect(() => {
    void attempt(async () => setInfo(await api.info()));
    void attempt(refresh);
  }, [attempt, refresh]);

  const add = (text: string) => attempt(async () => {
    await api.add(text);
    await refresh();
  });

  const remove = (id: number) => attempt(async () => {
    await api.remove(id);
    await refresh();
  });

  return { notes, info, error, setError, fail, add, remove };
}
