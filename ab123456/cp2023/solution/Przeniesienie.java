package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.exceptions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;

public class Przeniesienie extends TransefrAbstract{

    private final CountDownLatch zasowka;
    private CyclicBarrier bariera;
    private boolean czyZwalniacMuteksa;
    private boolean czyWCyklu;
    private boolean czyWSciezce;
    private boolean czyOstatniWSciezce;
    private Semaphore miejsceRzeczywiste;
    private Semaphore miejsceDoZwolnieniaNaKoniecSciezki; // pierwszy element sciezki przekazuje ostatniemu miejsce
    // rzeczywiste na ktore czeka, ostatni element sciezki wrzuca je do destDevice i na nim czeka

    public static boolean czyPoprawnePrzeniesienie(ComponentTransfer transfer,
                                                   StorageSystemImp system) throws TransferException{
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId()))
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getDestinationDeviceId())) // czy dev docelowy istnieje
            throw new DeviceDoesNotExist(transfer.getDestinationDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // czy dev zrodlowy istnieje
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(system.wTrakcieOperacji.get(transfer.getComponentId()))
            throw new ComponentIsBeingOperatedOn(transfer.getComponentId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // komponent nie znajduje sie na urzadzeniu zrodlowym
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getDestinationDeviceId())) // komponent znajduje sie juz na urz docelowym
            throw new ComponentDoesNotNeedTransfer(transfer.getComponentId(), transfer.getSourceDeviceId());

        return true;
    }
    public Przeniesienie(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
        zasowka = new CountDownLatch(1);
        czyZwalniacMuteksa = false;
    }
    private boolean czyIstniejeCykl(DeviceId zrodlo, int dlugoscCyklu){
        if(system.transferyDo.containsKey(zrodlo)){
            ConcurrentLinkedQueue<Przeniesienie> lista = system.transferyDo.get(zrodlo);
            for(Iterator<Przeniesienie> itr = lista.iterator(); itr.hasNext(); ){
                Przeniesienie przeniesienie = itr.next();
                // w tym miejscu caly czas porownujemy ze zrodlem tego samego przeniesienia
                if(przeniesienie.srcId.equals(destId) ) {
                    bariera = new CyclicBarrier(dlugoscCyklu + 1); // gdy stwierdzilismy istnienie cyklu tworzymy
                    // bariere cykliczna ktora oczekuje na wszystkie watki z cyklu
                    przeniesienie.bariera = bariera;// bariere trzeba przypisac przed obudzeniem
                    przeniesienie.czyWCyklu = true;

                    przeniesienie.zasowka.countDown(); // budzimy transfery pojedynczo
                    itr.remove();
                    if(lista.isEmpty())
                        system.transferyDo.remove(zrodlo);
                    return true;

                } else if (czyIstniejeCykl(przeniesienie.srcId, dlugoscCyklu + 1)) {
                    // jesli istnieje cykl to mozna obudzic te przeniesienia ktore don naleza
                    przeniesienie.bariera = bariera; // ustawiamy ta bariere wszystkim watkom
                    przeniesienie.czyWCyklu = true;

                    przeniesienie.zasowka.countDown(); // budzimy transfery pojedynczo
                    itr.remove();
                    if(lista.isEmpty())
                        system.transferyDo.remove(zrodlo);
                    return true;
                }
            }
        }
        return false;
    }

    // wykonuje transfer za pozwolneniem
    private void wykonaj(boolean pozwolenie){
        try {

            // fragment kodu odpowiedzialny za odopowiednie czekanie
            if(!pozwolenie) {
                zasowka.await(); // po obudzeniu z zasowki jestesmy w muteksie, ale nie zwalniamy go od razu bo na raz
                // budzi sie wiele watkow; zwalnia ten co obudzil
                system.wTrakcieOperacji.put(compId, true);
                system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
                if(czyWSciezce && czyOstatniWSciezce) { // ostani ze sciezki zwalnia miejsce
                    System.out.println("    PRZENIESNIE OSTATNIE W SCIEZECE " + this + " JEST W TRAKC ZWLANIANIA MSC NA " + srcId);
                    system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId);
                }
                bariera.await();
                if(czyZwalniacMuteksa){ // jesli nie jest w cyklu i zostal obudzony w sekcji krytycznej
                    // to musi podniesc muteksa
                    system.mutexPoprawnoscPozwolenie.release();
                }
            }

            if(czyWCyklu) {
                Semaphore semaforNaKomponentPrzenoszony = system.urzadzenia.get(srcId).dajIUsunOpuszczonySemafor(compId);
                transfer.prepare();
                bariera.await(); // gdy wszystkie transfery wykonaja prepare mozna przejsc dalej
                system.urzadzenia.get(destId).dodajOpuszczonySemafor(semaforNaKomponentPrzenoszony, compId);
            } else if (czyWSciezce) {
                if(czyOstatniWSciezce) {
                    miejsceDoZwolnieniaNaKoniecSciezki.acquire();
                    bariera.await();
                }
                else
                    bariera.await(); // reszta przypadkow czeka

                if(!czyOstatniWSciezce){ // tak samo jak w cyklu
                    Semaphore semaforNaKomponentPrzenoszony = system.urzadzenia.get(srcId).dajIUsunOpuszczonySemafor(compId);
                    transfer.prepare();
                    bariera.await(); // gdy wszystkie transfery wykonaja prepare mozna przejsc dalej
                    system.urzadzenia.get(destId).dodajOpuszczonySemafor(semaforNaKomponentPrzenoszony, compId);
                } else if (czyOstatniWSciezce) { // ostatni nie przekazuje dalej semafora ale go zwlanie
                    transfer.prepare();
                    system.urzadzenia.get(destId).dodajOpuszczonySemafor(miejsceDoZwolnieniaNaKoniecSciezki, compId);
                    // przedostatni element w sciezce dostaje semafor ktory na poczatku otrzymal pierwszy element ze
                    // sciezki
                    bariera.await();
                    system.urzadzenia.get(srcId).zwolnionoMiejsce(compId);
                }
            }
            else {
                transfer.prepare();
                system.urzadzenia.get(srcId).zwolnionoMiejsce(compId); // po wykonaniu prepare
                // "zwalniamy" miejsce na srcDev
                miejsceRzeczywiste.acquire(); // transfer przed wykonaniem perform musi sie upewnic
                // ze na urzadzeniu faktycznie znajduje sie miejsce
            }

            transfer.perform();

            system.wTrakcieOperacji.put(compId, false); // to nie musi byc w muteksie; transfery na tym komponencie moga
            // zaczac sie wykonywac nawet gdy reszta transferow z cyklu jest w trakcie wykonania

            system.mutexPoprawnoscPozwolenie.acquire();
            if (system.transferyDo.containsKey(destId)) {
                system.transferyDo.get(destId).remove(this); // czyscimy mape
                if(system.transferyDo.get(destId).isEmpty())
                    system.transferyDo.remove(destId);
            }
            system.mutexPoprawnoscPozwolenie.release();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        } catch (BrokenBarrierException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * metoda do budzenia pojedynczego transferu nie bedacego czescia cyklu
     */
    public void obudz(){
        bariera = new CyclicBarrier(1); // nie musi czekac na inne watki bo jest sam
        czyZwalniacMuteksa = true; // odziedziczyl sekcje krytyczna
        czyWCyklu = false;
        miejsceRzeczywiste = system.urzadzenia.get(destId).czekajNaMiejsce(compId); // wiemy ze nie bedziemy czekac bo
        // obudzilo nas usuwanie komponenetu

        system.wTrakcieOperacji.put(compId, true);
        system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId

        if(system.ileDodawanCZeka.containsKey(srcId)) { // w pierwszej kolejnosci sprawdzamy czy nie ma dodawania
            system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId);
            czyZwalniacMuteksa = false;
        }
        else if (system.transferyDo.containsKey(srcId)) { // sciezka
            DeviceId id = srcId;
            czyWSciezce = true;
            ArrayList<Przeniesienie> sciezka = new ArrayList<>();
            Przeniesienie temp;
            while (system.transferyDo.containsKey(id)) {
                temp = system.transferyDo.get(id).remove();
                sciezka.add(temp);

                if (system.transferyDo.get(id).isEmpty())
                    system.transferyDo.remove(id);

                id = temp.destId;
            }
            bariera = new CyclicBarrier(sciezka.size() + 1);
            sciezka.get(sciezka.size()-1).czyOstatniWSciezce = true; // ustawiam ostatni na sciezce
            sciezka.get(sciezka.size()-1).miejsceDoZwolnieniaNaKoniecSciezki = miejsceRzeczywiste;
            system.urzadzenia.get(destId).dajIUsunOpuszczonySemafor(compId); // od teraz tym miejscem zajmuje sie
            // ostatni element sciezki
            for(Przeniesienie przeniesienie : sciezka){
                przeniesienie.czyWSciezce = true;
                przeniesienie.bariera = bariera;
                przeniesienie.czyWCyklu = false;
                przeniesienie.zasowka.countDown();
            }
        }
        else {
            system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId);
        }

        zasowka.countDown();
    }

    @Override
    public boolean sprobujWykonacTransfer() {
        system.wTrakcieOperacji.put(compId, true); // do czasu wykonania transferu komponent nie moze byc poddany innym
        // transferom

        if (czyIstniejeCykl(srcId, 1) ) { // priorytet ma cykl
            czyWCyklu = true;
            system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
            try {
                bariera.await(); // czekamy az wszystkie watki zmodyfikuja metadane i zwalaniuamy muteksa
            } catch (BrokenBarrierException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            } catch (InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }
            system.mutexPoprawnoscPozwolenie.release();
            wykonaj(true);
            return true;
        } else if (system.urzadzenia.get(destId).czyWolne()) {
            czyWCyklu = false;
            miejsceRzeczywiste = system.urzadzenia.get(destId).czekajNaMiejsce(compId);
            system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
            if(system.ileDodawanCZeka.containsKey(srcId)) { // jesli czeka dodawnaie to dziedziczy sekcje krytyczna
                system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId); // informujemy ze na urzadzeniu srcId
                // zaraz pojawilo wolne miejsce
            }
            else if(system.transferyDo.containsKey(srcId)){ // obudzony transfer tez dziedziczy
                DeviceId id = srcId;
                czyWSciezce = true;
                ArrayList<Przeniesienie> sciezka = new ArrayList<>();
                Przeniesienie temp;
                while (system.transferyDo.containsKey(id)) {
                    temp = system.transferyDo.get(id).remove();
                    sciezka.add(temp);

                    if (system.transferyDo.get(id).isEmpty())
                        system.transferyDo.remove(id);

                    id = temp.destId;
                }
                bariera = new CyclicBarrier(sciezka.size() + 1);
                sciezka.get(sciezka.size()-1).czyOstatniWSciezce = true; // ustawiam ostatni na sciezce
                sciezka.get(sciezka.size()-1).miejsceDoZwolnieniaNaKoniecSciezki = miejsceRzeczywiste;
                system.urzadzenia.get(destId).dajIUsunOpuszczonySemafor(compId); // od teraz tym miejscem zajmuje sie
                // ostatni element sciezki
                for(Przeniesienie przeniesienie : sciezka){
                    przeniesienie.czyWSciezce = true;
                    przeniesienie.bariera = bariera;
                    przeniesienie.czyWCyklu = false;
                    przeniesienie.zasowka.countDown();
                }
            }
            else{
                system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId); // informujemy ze na urzadzeniu srcId
                // zaraz pojawilo wolne miejsce
                system.mutexPoprawnoscPozwolenie.release();
            }
            bariera = new CyclicBarrier(1); // nie musi sie zatrzymywac na barierze
            wykonaj(true);
            return true;

        } else { // cykl nie istnieje
            if (system.transferyDo.containsKey(destId))
                system.transferyDo.get(destId).add(this);
            else {
                ConcurrentLinkedQueue<Przeniesienie> nowa = new ConcurrentLinkedQueue<>();
                nowa.add(this);
                system.transferyDo.put(destId, nowa);
            }
            system.mutexPoprawnoscPozwolenie.release();
            wykonaj(false); // wieszamy go na zasowce
            return false;
        }
    }

    @Override
    public String toString(){
        return "Z " + srcId + " DO " + destId + " " + compId;
    }
}
