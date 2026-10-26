import { NotebookPen, SearchX } from 'lucide-react';
import { motion } from 'motion/react';

/** Shown when there is nothing to list: either no notes at all, or none that match the search. */
export function EmptyState({ searching }: { searching: boolean }) {
  const Icon = searching ? SearchX : NotebookPen;
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="grid justify-items-center gap-1.5 px-5 py-14 text-center text-muted"
    >
      <div className="mb-2 grid size-16 place-items-center rounded-2xl bg-linear-to-br from-accent-a/15 to-accent-b/15 text-accent-a">
        <Icon className="size-7" strokeWidth={1.6} />
      </div>
      <strong className="text-base text-ink">{searching ? 'No matching notes' : 'No notes yet'}</strong>
      <span>{searching ? 'Try a different search.' : 'Type something above and press Enter.'}</span>
    </motion.div>
  );
}
