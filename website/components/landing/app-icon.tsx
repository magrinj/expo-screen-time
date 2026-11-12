// Simplified stand-ins, not the brands' artwork.

export type AppId =
  | 'instagram' | 'tiktok' | 'x' | 'facebook' | 'youtube' | 'netflix' | 'spotify' | 'twitch'
  | 'whatsapp' | 'reddit' | 'pinterest' | 'screentime'
  | 'phone' | 'safari' | 'messages' | 'mail' | 'dialer' | 'chrome' | 'gmessages' | 'gmail';

export const APP_NAMES: Record<AppId, string> = {
  instagram: 'Instagram', tiktok: 'TikTok', x: 'X', facebook: 'Facebook', youtube: 'YouTube',
  netflix: 'Netflix', spotify: 'Spotify', twitch: 'Twitch', whatsapp: 'WhatsApp', reddit: 'Reddit',
  pinterest: 'Pinterest', screentime: 'Screen Time', phone: 'Phone', safari: 'Safari',
  messages: 'Messages', mail: 'Mail', dialer: 'Phone', chrome: 'Chrome', gmessages: 'Messages', gmail: 'Gmail',
};

const BG: Record<AppId, string> = {
  instagram: 'radial-gradient(circle at 30% 107%, #FDF497 0%, #FD5949 45%, #D6249F 60%, #285AEB 90%)',
  tiktok: '#000000', x: '#000000', facebook: '#0866FF', youtube: '#FFFFFF', netflix: '#000000',
  spotify: '#000000', twitch: '#9146FF', whatsapp: '#25D366', reddit: '#FF4500', pinterest: '#E60023',
  screentime: '#1E1D4A', phone: 'linear-gradient(180deg, #5BF675, #0CBD2A)', safari: '#FFFFFF',
  messages: 'linear-gradient(180deg, #5BF675, #0CBD2A)', mail: 'linear-gradient(180deg, #1A9BFF, #1E6CF2)',
  dialer: '#D3E3FD', chrome: '#FFFFFF', gmessages: '#0B57D0', gmail: 'linear-gradient(180deg, #1A9BFF, #1E6CF2)',
};

function Glyph({ id }: { id: AppId }) {
  const text = (glyph: string, size: number, color = '#FFFFFF', font = 'inherit') => (
    <span style={{ fontSize: size, color, fontFamily: font, fontWeight: 800, lineHeight: 1 }}>{glyph}</span>
  );
  switch (id) {
    case 'instagram':
      return (
        <svg width="34" height="34" viewBox="0 0 34 34"><rect x="3" y="3" width="28" height="28" rx="8.5" fill="none" stroke="#fff" strokeWidth="3" /><circle cx="17" cy="17" r="6.5" fill="none" stroke="#fff" strokeWidth="3" /><circle cx="25" cy="9" r="1.9" fill="#fff" /></svg>
      );
    case 'tiktok': return text('♪', 34);
    case 'x': return text('𝕏', 30);
    case 'facebook': return text('f', 40);
    case 'netflix': return text('N', 38, '#E50914');
    case 'twitch': return text('▮▮', 16);
    case 'reddit': return text('●', 30);
    case 'pinterest': return text('P', 36, '#FFFFFF', 'Georgia, serif');
    case 'youtube':
      return <svg width="40" height="28" viewBox="0 0 40 28"><rect width="40" height="28" rx="8" fill="#FF0000" /><path d="M16 8 L27 14 L16 20 Z" fill="#fff" /></svg>;
    case 'spotify':
      return (
        <svg width="40" height="40" viewBox="0 0 40 40"><circle cx="20" cy="20" r="19" fill="#1ED760" /><path d="M10 15 C17 12.5 25 13 31 16.5" fill="none" stroke="#000" strokeWidth="3.2" strokeLinecap="round" /><path d="M11.5 21 C17.5 19 24 19.5 28.5 22.3" fill="none" stroke="#000" strokeWidth="2.7" strokeLinecap="round" /><path d="M13 26.5 C18 25 23 25.5 26.5 27.5" fill="none" stroke="#000" strokeWidth="2.3" strokeLinecap="round" /></svg>
      );
    case 'whatsapp':
      return (
        <svg width="36" height="36" viewBox="0 0 36 36"><path d="M18 4 C10 4 4 10 4 17.5 C4 20.3 4.9 22.8 6.3 24.9 L4.5 31.5 L11.3 29.7 C13.3 30.9 15.6 31.5 18 31.5 C26 31.5 32 25.3 32 17.7 C32 10 26 4 18 4 Z" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinejoin="round" /><path d="M13 12.5 C13 18 17 22.5 23 23.5 L24.5 21 L21.5 19.5 L20 21 C17.5 20 15.8 18.2 15 16 L16.5 14.5 L15 11.5 Z" fill="#fff" /></svg>
      );
    case 'messages':
    case 'gmessages':
      return <svg width="36" height="32" viewBox="0 0 36 32"><path d="M18 3 C9 3 3 8.6 3 15 C3 18.6 5 21.8 8 24 C7.6 26.2 6.4 28 5 29.5 C8.4 29.3 11 28 12.6 26.4 C14.3 26.8 16.1 27 18 27 C27 27 33 21.4 33 15 C33 8.6 27 3 18 3 Z" fill="#fff" /></svg>;
    case 'phone':
    case 'dialer':
      return <svg width="30" height="30" viewBox="0 0 30 30"><path d="M7 3 L11.5 3 L13.5 9 L10.5 11.5 C12 15 15 18 18.5 19.5 L21 16.5 L27 18.5 L27 23 C27 25.2 25.2 27 23 27 C13.6 26.5 3.5 16.4 3 7 C3 4.8 4.8 3 7 3 Z" fill={id === 'dialer' ? '#0B57D0' : '#fff'} /></svg>;
    case 'safari':
      return <svg width="40" height="40" viewBox="0 0 40 40"><circle cx="20" cy="20" r="17" fill="#fff" /><circle cx="20" cy="20" r="14.5" fill="#1E9BF0" /><path d="M27 13 L22 22 L13 27 L18 18 Z" fill="#fff" /><path d="M27 13 L22 22 L18 18 Z" fill="#FF3B30" /></svg>;
    case 'chrome':
      return <svg width="40" height="40" viewBox="0 0 40 40"><circle cx="20" cy="20" r="18" fill="#DB4437" /><path d="M20 20 L35.6 29 A18 18 0 0 1 4.4 29 Z" fill="#0F9D58" /><path d="M20 2 A18 18 0 0 1 35.6 29 L20 20 Z" fill="#F4B400" /><circle cx="20" cy="20" r="8" fill="#fff" /><circle cx="20" cy="20" r="6.2" fill="#4285F4" /></svg>;
    case 'mail':
    case 'gmail':
      return <svg width="38" height="28" viewBox="0 0 38 28"><rect x="1" y="1" width="36" height="26" rx="4" fill="#fff" /><path d="M2.5 3 L19 16 L35.5 3" fill="none" stroke="#1E7CF2" strokeWidth="2.4" strokeLinejoin="round" /></svg>;
    case 'screentime':
      return (
        <svg width="58" height="58" viewBox="0 0 64 64"><path d="M17 15 L32 30 L47 15" fill="none" stroke="#F4F4FA" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" /><path d="M17 49 L32 34 L47 49 Z" fill="#A5A3FF" stroke="#A5A3FF" strokeWidth="5" strokeLinejoin="round" /></svg>
      );
  }
}

export function AppIcon({ id, size = 58, round = false, dim = false }: { id: AppId; size?: number; round?: boolean; dim?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className="relative flex shrink-0 items-center justify-center overflow-hidden"
      style={{ width: size, height: size, borderRadius: round ? '50%' : Math.round(size * 0.2337), background: BG[id] }}
    >
      <span className="flex" style={{ transform: `scale(${size / 58})` }}>
        <Glyph id={id} />
      </span>
      {dim ? <span className="absolute inset-0 bg-black/[0.52]" /> : null}
    </span>
  );
}
