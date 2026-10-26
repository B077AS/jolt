import { Trash2 } from 'lucide-react';
import { motion } from 'motion/react';
import type { Note } from '../api';

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

/** "3 hours ago", "yesterday", "just now". */
export function ago(timestamp: number, now = Date.now()): string {
  const seconds = Math.round((timestamp - now) / 1000);
  for (const [unit, size] of [['day', 86400], ['hour', 3600], ['minute', 60]] as const) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  }
  return 'just now';
}

/** One note. It slides in when added, slides out when deleted, and the others glide into the gap. */
export function NoteCard({ note, now, onDelete }: { note: Note; now: number; onDelete: () => void }) {
  return (
    <motion.li
      layout
      initial={{ opacity: 0, y: 10, scale: 0.985 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, x: 18, scale: 0.98, transition: { duration: 0.16 } }}
      transition={{ type: 'spring', stiffness: 420, damping: 34 }}
      className="group flex items-start gap-3 rounded-2xl border border-line bg-surface py-3.5 pr-3.5 pl-4 shadow-card transition-colors hover:border-accent-a/40"
    >
      <div className="min-w-0 flex-1">
        {/* React escapes text for us; there is no dangerouslySetInnerHTML anywhere in this app */}
        <div className="wrap-anywhere whitespace-pre-wrap">{note.text}</div>
        <div className="mt-0.5 text-xs text-muted" title={new Date(note.createdAt).toLocaleString()}>
          {ago(note.createdAt, now)}
        </div>
      </div>
      <button
        type="button"
        title="Delete"
        aria-label="Delete note"
        onClick={onDelete}
        className="grid size-8 place-items-center rounded-lg text-muted opacity-0 transition hover:bg-danger/10 hover:text-danger focus-visible:opacity-100 group-hover:opacity-100"
      >
        <Trash2 className="size-4" />
      </button>
    </motion.li>
  );
}
