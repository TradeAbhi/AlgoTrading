package com.trading.algo.service;

import java.util.List;
import org.springframework.stereotype.Service;

import com.trading.algo.ipo.Ipo;
import com.trading.algo.ipo.IpoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoService {

    private final IpoFetcherService fetcher;
    private final IpoRepository repo;

    public void syncIpos() throws Exception {
        List<Ipo> ipos = fetcher.fetchUpcomingIpos();
        int saved = 0, skipped = 0;

        for (Ipo ipo : ipos) {
            if (repo.findByName(ipo.getName()).isEmpty()) {
                repo.save(ipo);
                saved++;
            } else {
                skipped++;
            }
        }
        log.info("IPO sync done → saved: {}, skipped: {}", saved, skipped);
    }
}


//package com.trading.algo.service;
//
//import java.util.List;
//
//import org.springframework.data.domain.Example;
//import org.springframework.stereotype.Service;
//
//import com.trading.algo.entity.Ipo;
//import com.trading.algo.repo.IpoRepository;
//
//import lombok.RequiredArgsConstructor;
//
//@Service
//@RequiredArgsConstructor
//public class IpoService {
//
//    private final IpoFetcherService fetcher;
//    private final IpoRepository repo;
//
//    public void syncIpos() throws Exception {
//
//        List<Ipo> ipos = fetcher.fetchUpcomingIpos();
//
//        for (Ipo ipo : ipos) {
//
//            boolean exists = repo.exists(
//                Example.of(ipo)
//            );
//
//            if (!exists) {
//                repo.save(ipo);
//            }
//        }
//    }
//}