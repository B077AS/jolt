import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { runSmoke } from './smoke';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

if (window.__SMOKE__) {
  void runSmoke();
}
