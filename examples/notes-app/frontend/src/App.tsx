import { AlertCircle, Search, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useMemo, useState } from 'react';
import { Composer } from './components/Composer';
import { EmptyState } from './components/EmptyState';
import { Logo } from './components/Logo';
import { NoteCard } from './components/NoteCard';
import { ThemeSwitch } from './components/ThemeSwitch';
import { useTheme } from './theme';
import { useNotes } from './useNotes';

export function App() {
  const { notes, info, error, setError, fail, add, remove } = useNotes();
  const { preference, setPreference } = useTheme(fail);
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState('');
  const [now, setNow] = useState(() => Date.now());

  // keep "3 minutes ago" fresh
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return (notes ?? []).filter((note) => note.text.toLowerCase().includes(needle));
  }, [notes, query]);

  const submit = async () => {
    // clear the field only when the note was really added; if Java rejected it, the text stays so nothing is lost
    if (await add(draft)) setDraft('');
  };

  return (
    <>
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-line bg-canvas/80 px-5 py-3.5 backdrop-blur-md">
        <Logo />
        <h1 className="text-lg font-semibold tracking-tight">Notes</h1>
        {notes && notes.length > 0 && (
          <span className="rounded-full border border-line bg-surface px-2 py-px text-xs text-muted tabular-nums">{notes.length}</span>
        )}
        <span className="flex-1" />
        <ThemeSwitch value={preference} onChange={setPreference} />
      </header>

      <main className="mx-auto max-w-150 px-5 pt-5.5 pb-20">
        <Composer value={draft} onChange={setDraft} onSubmit={() => void submit()} />

        <div className="min-h-6">
          <AnimatePresence>
            {error && (
              <motion.div
                role="alert"
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
                className="overflow-hidden"
              >
                <div className="mt-3 flex items-center gap-2 rounded-xl bg-danger/10 px-3 py-2 text-sm text-danger">
                  <AlertCircle className="size-4 shrink-0" />
                  <span className="flex-1">{error}</span>
                  <button type="button" aria-label="Dismiss" onClick={() => setError('')} className="rounded p-0.5 hover:bg-danger/15">
                    <X className="size-3.5" />
                  </button>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {notes && notes.length > 3 && (
          <label className="mb-3 flex items-center gap-2 rounded-xl border border-line bg-surface px-3 py-2 text-sm focus-within:border-accent-a">
            <Search className="size-4 text-muted" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search notes"
              aria-label="Search notes"
              className="min-w-0 flex-1 bg-transparent outline-none placeholder:text-muted"
            />
          </label>
        )}

        <ul className="grid gap-2.5">
          <AnimatePresence initial={false}>
            {visible.map((note) => (
              <NoteCard key={note.id} note={note} now={now} onDelete={() => void remove(note.id)} />
            ))}
          </AnimatePresence>
        </ul>
        {notes && visible.length === 0 && <EmptyState searching={query.trim() !== ''} />}
      </main>

      <footer className="fixed inset-x-0 bottom-0 border-t border-line bg-canvas/85 px-5 py-2 text-center text-xs text-muted backdrop-blur-md">
        {info ? `React, TypeScript and Tailwind, running on ${info.os} with Java ${info.java}` : 'Starting...'}
      </footer>
    </>
  );
}
