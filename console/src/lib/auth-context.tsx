"use client";

/**
 * AuthContext — provides the current user (from GET /api/auth/me) and a
 * logout function to all client components in the authenticated shell.
 *
 * The Provider lives in the (auth) route-group layout so it wraps every
 * page inside the app shell. On mount it fetches /api/auth/me; if 401 it
 * redirects to /login.
 */

import React, { createContext, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, type ApiMeResponse } from "./api";

interface AuthState {
  me: ApiMeResponse | null;
  loading: boolean;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState>({
  me: null,
  loading: true,
  logout: async () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [me, setMe] = useState<ApiMeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api
      .getMe()
      .then((data) => {
        if (!cancelled) setMe(data);
      })
      .catch(() => {
        // 401 or network error → send to login
        if (!cancelled) router.replace("/login");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  const logout = async () => {
    try {
      await api.logout();
    } catch {
      // best-effort
    }
    setMe(null);
    router.replace("/login");
  };

  return (
    <AuthContext.Provider value={{ me, loading, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  return useContext(AuthContext);
}
