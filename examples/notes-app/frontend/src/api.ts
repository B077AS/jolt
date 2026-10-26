import { call } from './jolt';

// One typed function per handler registered in NotesApp.java. If you rename a handler there, change it here.
// (Generating this file from the Java side is planned, not built.)

export interface Note {
  id: number;
  text: string;
  createdAt: number;
}

export interface AppInfo {
  os: string;
  java: string;
}

export type Preference = 'system' | 'light' | 'dark';
export type Scheme = 'light' | 'dark';

export interface ThemeRequest {
  preference: Preference;
  scheme: Scheme;
  /** Page background as #rrggbb; Java uses it for the title bar and the web view background. */
  background: string;
  /** Page text colour as #rrggbb; Java uses it for the title bar text. */
  text: string;
}

export const api = {
  info: () => call<AppInfo>('app.info'),
  list: () => call<Note[]>('notes.list'),
  add: (text: string) => call<Note>('notes.add', { text }),
  remove: (id: number) => call<{ deleted: boolean }>('notes.delete', { id }),
  getPreference: () => call<{ preference: Preference }>('settings.get'),
  /** Restyles the native window (title bar, background) and remembers the choice. */
  setTheme: (theme: ThemeRequest) => call<{ ok: boolean }>('window.setTheme', { ...theme }),
};
