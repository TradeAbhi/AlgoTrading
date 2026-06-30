import { useState } from 'react';
import UsWeeklyBreakout from './components/UsWeeklyBreakout';
import WeeklyBreakout from './components/WeeklyBreakout';
import IpoScanner from './components/IpoScanner';
import DeltaScanner from './components/DeltaScanner';
import Backtest from './components/Backtest';
import FiboBacktest from './components/FiboBacktest';
import LiveStrategy from './components/LiveStrategy';
import MarketSentiment from './components/MarketSentiment';
import Watchlist from './components/Watchlist';
import IndexStrength from './components/IndexStrength';
import MoverAnalysis from './components/MoverAnalysis';
import StockAnalyzer from './components/StockAnalyzer';
import Nse52WeekHigh from './components/Nse52WeekHigh';
import Portfolio from './components/Portfolio';
import BacktestWinnerAnalysis from './components/BacktestWinnerAnalysis';

function App() {
  const [activeScanner, setActiveScanner] = useState('us-weekly');

  return (
    <div style={styles.container}>
      <h1 style={styles.title}>🚀 Algo Trading Dashboard</h1>
      
      {/* Scanner Navigation */}
      <div style={styles.scannerNav}>
        <button
          onClick={() => setActiveScanner('us-weekly')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'us-weekly' ? styles.activeNavButton : {})
          }}
        >
          🇺🇸 US Weekly Breakout
        </button>
        <button
          onClick={() => setActiveScanner('nse-weekly')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'nse-weekly' ? styles.activeNavButton : {})
          }}
        >
          🇮🇳 NSE Weekly Breakout
        </button>
        <button
          onClick={() => setActiveScanner('nse-52week')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'nse-52week' ? styles.activeNavButton : {})
          }}
        >
          📈 NSE 52-Week High
        </button>
        <button
          onClick={() => setActiveScanner('ipo')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'ipo' ? styles.activeNavButton : {})
          }}
        >
          📈 IPO Scanner
        </button>
        <button
          onClick={() => setActiveScanner('delta')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'delta' ? styles.activeNavButton : {})
          }}
        >
          📊 Delta Scanner
        </button>
        <button
          onClick={() => setActiveScanner('backtest')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'backtest' ? styles.activeNavButton : {})
          }}
        >
          📈 Backtest
        </button>
        <button
          onClick={() => setActiveScanner('fibo-backtest')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'fibo-backtest' ? styles.activeNavButton : {})
          }}
        >
          📊 Fibo Backtest
        </button>
        <button
          onClick={() => setActiveScanner('live-strategy')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'live-strategy' ? styles.activeNavButton : {})
          }}
        >
          🔴 Live Strategy
        </button>
        <button
          onClick={() => setActiveScanner('sentiment')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'sentiment' ? styles.activeNavButton : {})
          }}
        >
          📊 Sentiment
        </button>
        <button
          onClick={() => setActiveScanner('watchlist')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'watchlist' ? styles.activeNavButton : {})
          }}
        >
          📋 Watchlist
        </button>
        <button
          onClick={() => setActiveScanner('index-strength')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'index-strength' ? styles.activeNavButton : {})
          }}
        >
          📈 Index Strength
        </button>
        <button
          onClick={() => setActiveScanner('mover-analysis')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'mover-analysis' ? styles.activeNavButton : {})
          }}
        >
          📊 Mover Analysis
        </button>
        <button
          onClick={() => setActiveScanner('stock-analyzer')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'stock-analyzer' ? styles.activeNavButton : {})
          }}
        >
          🔬 Stock Analyzer
        </button>
        <button
          onClick={() => setActiveScanner('portfolio')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'portfolio' ? styles.activeNavButton : {})
          }}
        >
          💼 Portfolio
        </button>
        <button
          onClick={() => setActiveScanner('backtest-winner')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'backtest-winner' ? styles.activeNavButton : {})
          }}
        >
          🏆 Winner Analysis
        </button>
      </div>

      {/* Scanner Content */}
      {activeScanner === 'us-weekly' && <UsWeeklyBreakout />}
      {activeScanner === 'nse-weekly' && <WeeklyBreakout />}
      {activeScanner === 'nse-52week' && <Nse52WeekHigh />}
      {activeScanner === 'ipo' && <IpoScanner />}
      {activeScanner === 'delta' && <DeltaScanner />}
      {activeScanner === 'backtest' && <Backtest />}
      {activeScanner === 'fibo-backtest' && <FiboBacktest />}
      {activeScanner === 'live-strategy' && <LiveStrategy />}
      {activeScanner === 'sentiment' && <MarketSentiment />}
      {activeScanner === 'watchlist' && <Watchlist />}
      {activeScanner === 'index-strength' && <IndexStrength />}
      {activeScanner === 'mover-analysis' && <MoverAnalysis />}
      {activeScanner === 'stock-analyzer' && <StockAnalyzer />}
      {activeScanner === 'portfolio' && <Portfolio />}
      {activeScanner === 'backtest-winner' && <BacktestWinnerAnalysis />}
    </div>
  );
}

const styles = {
  container: {
    maxWidth: '1400px',
    margin: '0 auto',
    padding: '20px',
    background: 'white',
    borderRadius: '12px',
    boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)'
  },
  title: {
    textAlign: 'center',
    color: '#333',
    marginBottom: '30px',
    fontSize: '32px'
  },
  scannerNav: {
    display: 'flex',
    gap: '10px',
    marginBottom: '30px',
    flexWrap: 'wrap',
    justifyContent: 'center'
  },
  navButton: {
    padding: '12px 24px',
    background: '#e0e0e0',
    color: '#333',
    border: 'none',
    borderRadius: '8px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
    transition: 'all 0.2s'
  },
  activeNavButton: {
    background: '#667eea',
    color: 'white'
  }
};

export default App;
