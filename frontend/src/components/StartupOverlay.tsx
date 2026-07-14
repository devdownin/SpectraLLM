import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';


export const StartupOverlay: React.FC = () => {
    const [installations, setInstallations] = useState<any[]>([]);
    const [isVisible, setIsVisible] = useState(false);

    useEffect(() => {
        // Interroge l'API pour voir s'il y a des installations en cours (déclenchées par StartupOrchestrator)
        const checkInstallations = async () => {
            try {
                const res = await axios.get<any[]>('/api/models/hub/installations');
                const active = res.data.filter(i => i.status === 'DOWNLOADING' || i.status === 'PENDING' || i.status === 'REGISTERING');
                if (active.length > 0) {
                    setInstallations(active);
                    setIsVisible(true);
                } else {
                    if (document.getElementById('startup-overlay')) {
                        toast.success("Modèles prêts", {
                            description: "Démarrage du moteur d'inférence en cours... Cela peut prendre quelques secondes."
                        });
                    }
                    setIsVisible(false);
                }
            } catch (err) {
                console.error("Erreur check installations", err);
            }
        };

        checkInstallations();
        const interval = setInterval(checkInstallations, 2000);
        return () => clearInterval(interval);
    }, []);

    if (!isVisible) return null;

    return (
        <div id="startup-overlay" className="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm flex items-center justify-center">
            <div className="bg-card p-8 rounded-lg shadow-lg max-w-lg w-full text-center border border-border">
                <Loader2 className="w-12 h-12 animate-spin text-primary mx-auto mb-6" />
                <h2 className="text-2xl font-bold mb-2">Préparation de Spectra</h2>
                <p className="text-muted-foreground mb-6">
                    Téléchargement des modèles d'IA nécessaires pour le premier lancement...
                </p>

                <div className="space-y-4">
                    {installations.map((job) => (
                        <div key={job.jobId} className="text-left bg-muted p-4 rounded">
                            <div className="flex justify-between items-center mb-2">
                                <span className="font-semibold text-sm">{job.modelName}</span>
                                <span className="text-xs text-muted-foreground">{job.status}</span>
                            </div>
                            <div className="w-full bg-background rounded-full h-2.5">
                                <div
                                    className="bg-primary h-2.5 rounded-full transition-all duration-300"
                                    style={{ width: `${job.progress || 0}%` }}
                                ></div>
                            </div>
                            <div className="text-right text-xs mt-1 text-muted-foreground">
                                {job.progress || 0}%
                            </div>
                        </div>
                    ))}
                </div>
                <p className="text-xs text-muted-foreground mt-6">
                    Cela peut prendre plusieurs minutes selon votre connexion.
                </p>
            </div>
        </div>
    );
};
