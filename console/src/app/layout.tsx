import type { Metadata } from "next";
import { IBM_Plex_Sans, IBM_Plex_Mono } from "next/font/google";
import { AuthProvider } from "@/lib/auth-context";
import { LanguageProvider } from "@/lib/i18n";
import { FeedbackProvider } from "@/lib/feedback";
import "./globals.css";

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

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={`${plexSans.variable} ${plexMono.variable}`}>
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
