package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.DeviceId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class Urzadzenie {
    private final ConcurrentHashMap<ComponentId, Semaphore> opuszczoneSemafory; // miejsca zajete  w trakcie zwalniania
    // i nie tylko
    private final LinkedBlockingQueue<Semaphore> podniesioneSemafory; // wolne miejsca; w tej kolejce nie pojawi sie
    // zaden element jesli kolejnka zwroconeOpuszczoneSemafory jest pusta
    private final LinkedBlockingQueue<Semaphore> zwroconeOpuszczoneSemafory; // miejsca w trakcie zwalniania

    private Semaphore muteks;
    public Urzadzenie(int wszystkieMiejsca, Map<ComponentId, DeviceId> compPlacement, DeviceId devId){
        muteks = new Semaphore(1);
        opuszczoneSemafory = new ConcurrentHashMap<>();
        podniesioneSemafory = new LinkedBlockingQueue<>();
        zwroconeOpuszczoneSemafory = new LinkedBlockingQueue<>();
        for(Map.Entry<ComponentId, DeviceId> para : compPlacement.entrySet()){
            if(devId.equals(para.getValue()))
                opuszczoneSemafory.put(para.getKey(), new Semaphore(0));
        }
        for(int i = 0; i <wszystkieMiejsca - opuszczoneSemafory.size(); i++)
            podniesioneSemafory.add(new Semaphore(1));
    }

    /**
     * metoda do wywolania przez procedure dodajaca komponent na urzadnie (tj. Dodanie luc PRzeniesieni) w momencie
     * uzyskania pozwolenia. Zwraca semafor do czekania na miejsce do wykonania metody perform. Nie wiadomo czy semafor
     * bedzie podniesiony czy opuszczony
     * @param komponent ktorego dotyczy dodanie
     * @return
     */
    public Semaphore czekajNaMiejsce(ComponentId komponent){
        Semaphore semafor;
        try {
            muteks.acquire();
            if(!podniesioneSemafory.isEmpty()) { // nie moze byc takiej sytuacji ze w tej kolejce sie cos pojawi jak ta
                // druga jest pusta
                semafor = podniesioneSemafory.remove();
                opuszczoneSemafory.put(komponent, semafor);
                muteks.release();
                return semafor;
            }
            for(Semaphore semafor2 : zwroconeOpuszczoneSemafory){ // jesli jest w kolejce sem podniesiony to go zwracamy
                if(semafor2.availablePermits() > 0) {
                    zwroconeOpuszczoneSemafory.remove(semafor2);
                    opuszczoneSemafory.put(komponent, semafor2);
                    muteks.release();
                    return semafor2;
                }
            }

            muteks.release();

            semafor = zwroconeOpuszczoneSemafory.take(); // czekamy dopoki jakies miejsce sie nie zwolni
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

        opuszczoneSemafory.put(komponent, semafor); // do momentu zwolnienia miejsca ten semafor bedzie w 2 roznych
        // miejscach w mapie
        return semafor;
    }


    /**
     * wywoalnie tej procedury nalezy do funkcji zwalniajacej miejsce na urzadzeniu (tj. Usuniecie lub Przeniesieni) w
     * momencie uzyskania pozwolenia
     * @param komponent komponent ktorego dotyczy operacja zwalaniana
     */
    public void slotBeingFreed(ComponentId komponent){
        zwroconeOpuszczoneSemafory.add(opuszczoneSemafory.get(komponent));
        System.out.println("    KOMPONENT " + komponent + " JEST W TRAKCIE ZWALNIANIA MSC");
    }

    /**
     * wywolanie tej metody nalezy do procedury zwalniajacej miejsce na urzadzeniu (tj. Usuniecie lub Przeniesieni) po
     * wywolaniu funkcji prepare
     * @param komponent komponent ktorego dotyczy operacja zwalaniana
     */
    public void slotFreed(ComponentId komponent){
        try {
            muteks.acquire();
            Semaphore zwolnioneMiejsce = opuszczoneSemafory.get(komponent);
            zwolnioneMiejsce.release();
            opuszczoneSemafory.remove(komponent);
            muteks.release();
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * zwraca true jesli jest jakies miejsce wolne lub w trkacie zwalniania
     * @return
     */
    public boolean czyWolne(){
        if(zwroconeOpuszczoneSemafory.size() > 0 || podniesioneSemafory.size() > 0)
            return true;
        return false;
    }

    /**
     * wywolywane przy znalezieniu cyklu razem z dodajOpuszczonySemafor
     * @param komp komponent
     * @return
     */
    public Semaphore getLoweredSem(ComponentId komp){
        return opuszczoneSemafory.remove(komp);
    }

    /**
     * wywolywane przy znalezieniu cyklu razem z dajIUsunOpuszczonySemafor
     * @param komp komponent
     * @return
     */
    public void addLoweredSem(Semaphore semafor, ComponentId komp){
        opuszczoneSemafory.put(komp, semafor);
    }
}
