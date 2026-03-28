import React, { useState, useEffect } from 'react';

const ReportPage: React.FC = () => {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [isAuthenticated, setIsAuthenticated] = useState<boolean | null>(null);

    const AUTH_URL = process.env.REACT_APP_AUTH_URL || 'http://localhost:8001';
    const API_URL = process.env.REACT_APP_API_URL || 'http://localhost:8002';
    const KEYCLOAK_URL = process.env.REACT_APP_KEYCLOAK_URL || 'http://localhost:8080';
    const REALM = process.env.REACT_APP_KEYCLOAK_REALM || 'reports-realm';
    const CLIENT_ID = process.env.REACT_APP_KEYCLOAK_CLIENT_ID || 'reports-frontend';

    useEffect(() => {
        const urlParams = new URLSearchParams(window.location.search);
        const code = urlParams.get('code');

        if (code) {
            exchangeCode(code);
        } else {
            checkAuth();
        }
    }, []);

    const checkAuth = async (): Promise<void> => {
        try {
            const response = await fetch(`${AUTH_URL}/api/auth/check`, {
                credentials: 'include'
            });
            const data = await response.json();
            setIsAuthenticated(data.authenticated);
        } catch (err) {
            console.error('Auth check failed:', err);
            setIsAuthenticated(false);
        }
    };

    const exchangeCode = async (code: string): Promise<void> => {
        const codeVerifier = sessionStorage.getItem('pkce_verifier');

        try {
            const response = await fetch(`${AUTH_URL}/api/auth/token`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    code,
                    redirect_uri: window.location.origin,
                    code_verifier: codeVerifier
                }),
                credentials: 'include'
            });

            if (response.ok) {
                sessionStorage.removeItem('pkce_verifier');
                window.history.replaceState({}, '', window.location.pathname);
                setIsAuthenticated(true);
            } else {
                const errorData = await response.json();
                setError(errorData.error || 'Authentication failed');
                setIsAuthenticated(false);
            }
        } catch (err) {
            setError('Network error during authentication');
            setIsAuthenticated(false);
        }
    };

    const generateCodeVerifier = (): string => {
        const array = new Uint8Array(32);
        window.crypto.getRandomValues(array);
        // Преобразуем через Array.from для совместимости
        const chars = Array.from(array).map(byte => String.fromCharCode(byte)).join('');
        return btoa(chars)
            .replace(/=/g, '')
            .replace(/\+/g, '-')
            .replace(/\//g, '_');
    };

    const generateCodeChallenge = async (verifier: string): Promise<string> => {
        const encoder = new TextEncoder();
        const data = encoder.encode(verifier);
        const hash = await window.crypto.subtle.digest('SHA-256', data);
        const hashArray = Array.from(new Uint8Array(hash));
        const hashChars = hashArray.map(byte => String.fromCharCode(byte)).join('');
        return btoa(hashChars)
            .replace(/=/g, '')
            .replace(/\+/g, '-')
            .replace(/\//g, '_');
    };

    const handleLogin = async (): Promise<void> => {
        console.log('🔐 PKCE Login started');

        const codeVerifier = generateCodeVerifier();
        console.log('Verifier generated');
        sessionStorage.setItem('pkce_verifier', codeVerifier);

        const codeChallenge = await generateCodeChallenge(codeVerifier);
        console.log('Challenge generated');

        const params = new URLSearchParams({
            client_id: CLIENT_ID,
            redirect_uri: window.location.origin,
            response_type: 'code',
            scope: 'openid profile email',
            code_challenge_method: 'S256',
            code_challenge: codeChallenge
        });

        const url = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/auth?${params}`;
        console.log('Redirect URL:', url);
        window.location.href = url;
    };

    const downloadReport = async (): Promise<void> => {
        try {
            setLoading(true);
            setError(null);

            var report_id = "prosthetic_1"

            const response = await fetch(`${API_URL}/api/reports/${report_id}`, {
                method: "GET",
                credentials: "include"
            });

            if (response.status === 401) {
                const refreshResponse = await fetch(`${AUTH_URL}/api/auth/refresh`, {
                    method: 'POST',
                    credentials: 'include'
                });

                if (refreshResponse.ok) {
                    return downloadReport();
                } else {
                    setIsAuthenticated(false);
                    return;
                }
            }

            if (!response.ok) {
                throw new Error(`HTTP error ${response.status}`);
            }

            // Получаем текст отчёта
            const text = await response.text();
            console.log("✅ Report data:", text);

            // Скачиваем как файл
            const blob = new Blob([text], { type: 'text/plain' });
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `report.txt`;
            a.click();
            window.URL.revokeObjectURL(url);

            alert('Report downloaded! Check console for data.');

        } catch (err) {
            setError(err instanceof Error ? err.message : 'An error occurred');
        } finally {
            setLoading(false);
        }
    };

    const handleLogout = async (): Promise<void> => {
        try {
            await fetch(`${AUTH_URL}/api/auth/logout`, {
                method: 'POST',
                credentials: 'include'
            });
            setIsAuthenticated(false);
        } catch (err) {
            console.error('Logout failed:', err);
        }
    };

    if (isAuthenticated === null) {
        return (
            <div className="flex justify-center items-center min-h-screen bg-gray-100">
                <div className="text-gray-600">Loading...</div>
            </div>
        );
    }

    if (!isAuthenticated) {
        return (
            <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
                <div className="p-8 bg-white rounded-lg shadow-md text-center">
                    <h1 className="text-2xl font-bold mb-6 text-gray-800">BionicPRO Reports</h1>
                    <button
                        onClick={handleLogin}
                        className="px-6 py-3 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors"
                    >
                        Login
                    </button>
                    {error && (
                        <div className="mt-4 p-3 bg-red-100 text-red-700 rounded text-sm">
                            {error}
                        </div>
                    )}
                </div>
            </div>
        );
    }

    return (
        <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
            <div className="p-8 bg-white rounded-lg shadow-md text-center">
                <h1 className="text-2xl font-bold mb-6 text-gray-800">Usage Reports</h1>

                <div className="space-x-4">
                    <button
                        onClick={downloadReport}
                        disabled={loading}
                        className={`px-6 py-3 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors ${
                            loading ? 'opacity-50 cursor-not-allowed' : ''
                        }`}
                    >
                        {loading ? 'Generating...' : 'Download Report'}
                    </button>

                    <button
                        onClick={handleLogout}
                        className="px-6 py-3 bg-gray-500 text-white rounded-lg hover:bg-gray-600 transition-colors"
                    >
                        Logout
                    </button>
                </div>

                {error && (
                    <div className="mt-4 p-3 bg-red-100 text-red-700 rounded text-sm">
                        {error}
                    </div>
                )}
            </div>
        </div>
    );
};

export default ReportPage;