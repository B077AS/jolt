import { Plus } from 'lucide-react';
import type { FormEvent } from 'react';

/** The "write a note" box. Enter or the Add button submits; the parent clears the text when the note was added. */
export function Composer({ value, onChange, onSubmit }: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <form
      onSubmit={submit}
      className="flex gap-2 rounded-2xl border border-line bg-surface p-2 shadow-card transition focus-within:border-accent-a focus-within:ring-4 focus-within:ring-accent-a/25"
    >
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Write a note, then press Enter"
        aria-label="New note"
        autoComplete="off"
        autoFocus
        className="min-w-0 flex-1 bg-transparent px-2.5 py-2 text-[15px] outline-none placeholder:text-muted"
      />
      <button
        className="inline-flex items-center gap-1.5 rounded-[10px] bg-linear-to-br from-accent-a to-accent-b px-3.5 font-semibold text-white transition hover:brightness-110 active:scale-[.97]"
      >
        <Plus className="size-4" strokeWidth={2.4} />
        Add
      </button>
    </form>
  );
}
