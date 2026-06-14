import type { Metadata } from "next";
import { cookies } from "next/headers";
import { IBM_Plex_Sans, IBM_Plex_Mono } from "next/font/google";
import { AuthProvider } from "@/lib/auth-context";
import { LanguageProvider } from "@/lib/i18n";
import { FeedbackProvider } from "@/lib/feedback";
import "./globals.css";

// Mirror of LANG_COOKIE in src/lib/i18n.tsx (kept here so the server layout
// doesn't import the client i18n module just for a constant).
const LANG_COOKIE = "vouchq-lang";

const plexSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex-sans",
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "vouchq Console",
  description: "Governance & trust registry for AI agent capabilities",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // SSR the right <html lang> from the persisted locale cookie (MA3-129) so AT
  // gets the correct language on first paint, not just after client hydration.
  const cookieLang = (await cookies()).get(LANG_COOKIE)?.value;
  const lang = cookieLang === "ko" ? "ko" : "en";
  return (
    <html lang={lang} className={`${plexSans.variable} ${plexMono.variable}`}>
      <body>
        {/*
          AuthProvider wraps the entire app so all client components can call
          useAuth(). The /login page ignores the auth state (no AppShell).
          All AppShell pages check auth on mount via the context.
        */}
        <LanguageProvider>
          <FeedbackProvider>
            <AuthProvider>{children}</AuthProvider>
          </FeedbackProvider>
        </LanguageProvider>
      </body>
    </html>
  );
}
