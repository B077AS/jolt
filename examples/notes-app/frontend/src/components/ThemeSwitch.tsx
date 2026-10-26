import { Monitor, Moon, Sun } from 'lucide-react';
import { motion } from 'motion/react';
import type { Preference } from '../api';

const OPTIONS = [
  { value: 'system', label: 'Match the system', icon: Monitor },
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
] as const;

/** A three-way switch: system, light or dark. The selected option has a pill that slides between choices. */
export function ThemeSwitch({ value, onChange }: { value: Preference; onChange: (value: Preference) => void }) {
  return (
    <div role="group" aria-label="Theme" className="relative inline-flex gap-0.5 rounded-xl border border-line bg-surface p-[3px]">
      {OPTIONS.map(({ value: option, label, icon: Icon }) => {
        const selected = option === value;
        return (
          <button
            key={option}
            type="button"
            title={label}
            aria-label={label}
            aria-pressed={selected}
            onClick={() => onChange(option)}
            className={`relative grid h-7 w-8 place-items-center rounded-lg outline-offset-2 transition-colors focus-visible:outline-2 focus-visible:outline-accent-a ${
              selected ? 'text-ink' : 'text-muted hover:text-ink'
            }`}
          >
            {selected && (
              <motion.span
                layoutId="theme-pill"
                className="absolute inset-0 rounded-lg border border-line bg-canvas"
                transition={{ type: 'spring', stiffness: 500, damping: 36 }}
              />
            )}
            <Icon className="relative size-4" strokeWidth={2} />
          </button>
        );
      })}
    </div>
  );
}
