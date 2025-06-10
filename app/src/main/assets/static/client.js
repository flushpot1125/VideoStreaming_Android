document.addEventListener('DOMContentLoaded', () => {
    // DOM要素
    const videoElem = document.getElementById('videoStream');
    const statusElem = document.getElementById('status');
    const audioToggleBtn = document.getElementById('audioToggle');
    const audioIndicator = document.getElementById('audioIndicator');

    // 設定
    const config = {
        reconnectInterval: 2000,     // 初期再接続間隔（ミリ秒）
        maxReconnectInterval: 30000, // 最大再接続間隔（ミリ秒）
        reconnectDecay: 1.5,         // 再接続間隔の増加率
        maxReconnectAttempts: 20,    // 最大再接続試行回数
        connectionTimeout: 10000,    // 接続タイムアウト（ミリ秒）
        pingInterval: 5000,          // Ping送信間隔（ミリ秒）
        pongTimeout: 10000,          // Pong応答タイムアウト（ミリ秒）
        debug: true                  // デバッグモード
    };

    // 内部状態
    const state = {
        ws: null,                    // WebSocketインスタンス
        wsUrl: null,                 // WebSocket URL
        wsReconnectTimer: null,      // 再接続タイマー
        wsConnectingTimer: null,     // 接続中タイマー
        wsPingTimer: null,           // ping送信タイマー
        wsPongTimer: null,           // pong待ちタイマー
        reconnectAttempts: 0,        // 再接続試行回数
        clientId: null,              // サーバーから割り当てられたクライアントID
        lastPongTime: 0,             // 最後にPongを受け取った時間

        // オーディオ関連
        audioContext: null,          // AudioContext
        audioProcessor: null,        // オーディオプロセッサ
        audioEnabled: true,          // 音声有効フラグ
        audioBuffer: [],             // オーディオバッファ

        // 統計情報
        stats: {
            videoFrames: 0,
            audioFrames: 0,
            lastVideoTime: 0,
            lastAudioTime: 0,
            errors: 0
        }
    };

    // ステータス表示更新
    function updateStatus(message, isError = false) {
        if (config.debug) console.log(`[Status] ${message}`);

        if (!statusElem) return;
        statusElem.textContent = message;
        statusElem.style.color = isError ? 'red' : 'green';
    }

    // デバッグログ
    function log(...args) {
        if (config.debug) console.log('[WebSocket]', ...args);
    }

    // エラーログ
    function logError(...args) {
        console.error('[WebSocket Error]', ...args);
    }

    // WebSocketの接続を開始
    function connect() {
        // 既存接続のクリーンアップ
        cleanup();

        // URL設定
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        state.wsUrl = `${protocol}//${window.location.host}/media`;

        // 接続タイムアウトの設定
        state.wsConnectingTimer = setTimeout(() => {
            if (state.ws && state.ws.readyState !== WebSocket.OPEN) {
                logError('接続タイムアウト');
                updateStatus('接続タイムアウト - 再接続します...', true);
                reconnect();
            }
        }, config.connectionTimeout);

        try {
            log(`接続中: ${state.wsUrl}`);
            updateStatus('サーバーに接続中...');

            // WebSocketインスタンス作成
            state.ws = new WebSocket(state.wsUrl);

            // イベントハンドラ設定
            state.ws.addEventListener('open', handleOpen);
            state.ws.addEventListener('message', handleMessage);
            state.ws.addEventListener('error', handleError);
            state.ws.addEventListener('close', handleClose);
        } catch (e) {
            logError('WebSocket作成エラー:', e);
            updateStatus('接続エラー - 再接続します...', true);
            reconnect();
        }
    }

    // 接続成功時の処理
    function handleOpen(event) {
        log('接続確立');
        updateStatus('サーバーに接続しました');

        // タイマーをクリア
        clearTimeout(state.wsConnectingTimer);

        // カウンタリセット
        state.reconnectAttempts = 0;
        state.lastPongTime = Date.now();

        // クライアント情報送信
        sendClientInfo();

        // Pingタイマー開始
        startPingTimer();

        // オーディオ初期化
        initAudioIfNeeded();
    }

    // クライアント情報送信
    function sendClientInfo() {
        if (!isConnected()) return;

        try {
            const info = {
                type: 'clientInfo',
                userAgent: navigator.userAgent,
                platform: navigator.platform,
                screenSize: `${window.screen.width}x${window.screen.height}`,
                timestamp: Date.now()
            };

            state.ws.send(JSON.stringify(info));
            log('クライアント情報を送信しました');
        } catch (e) {
            logError('クライアント情報送信エラー:', e);
        }
    }

    // メッセージ受信時の処理
    async function handleMessage(event) {
        // テキストメッセージの処理
        if (typeof event.data === 'string') {
            try {
                // 単純な "pong" メッセージの処理
                if (event.data === 'pong') {
                    handlePong();
                    return;
                }

                // JSON形式のメッセージをパース
                const data = JSON.parse(event.data);

                // メッセージタイプに応じた処理
                if (data.type) {
                    switch (data.type) {
                        case 'metadata':
                            log('メタデータ受信:', data);
                            if (data.clientId) {
                                state.clientId = data.clientId;
                            }
                            break;

                        case 'heartbeat':
                            handlePong();
                            break;

                        case 'status':
                            log('ステータス更新:', data);
                            break;

                        case 'serverShutdown':
                            updateStatus('サーバーがシャットダウンしました - 再接続します...', true);
                            reconnect();
                            break;

                        default:
                            log('不明なメッセージタイプ:', data);
                    }
                }
            } catch (e) {
                logError('テキストメッセージ処理エラー:', e);
            }
            return;
        }

        // バイナリデータの処理
        try {
            const buffer = await event.data.arrayBuffer();

            // データが小さすぎる場合はスキップ
            if (buffer.byteLength < 5) {
                logError('無効なバイナリメッセージ: サイズが小さすぎます');
                return;
            }

            const view = new DataView(buffer);

            try {
                // フレームタイプとサイズ取得
                const frameType = view.getUint8(0);
                const dataSize = view.getUint32(1, false); // ビッグエンディアン

                // データサイズが妥当か確認
                if (dataSize > buffer.byteLength - 5) {
                    logError(`無効なデータサイズ: ${dataSize}, バッファサイズ: ${buffer.byteLength}`);
                    return;
                }

                // データ部分を取得
                const frameData = buffer.slice(5, 5 + dataSize);

                // フレームタイプに応じた処理
                if (frameType === 0) {
                    // ビデオフレーム処理
                    processVideoFrame(frameData);
                } else if (frameType === 1) {
                    // オーディオフレーム処理
                    processAudioFrame(frameData);
                }
            } catch (e) {
                logError('フレームデータ解析エラー:', e);
                state.stats.errors++;
            }
        } catch (e) {
            logError('バイナリデータ処理エラー:', e);
            state.stats.errors++;
        }
    }

    // ビデオフレーム処理
    // ビデオフレーム処理関数の修正
    function processVideoFrame(frameData) {
        if (!videoElem) return;

        try {
            // データサイズをログ出力
            console.log(`ビデオフレーム受信: ${frameData.byteLength} bytes`);

            // フレームレート制限 (30fpsまで)
            const now = Date.now();
            if (now - state.stats.lastVideoTime < 33) {
                return;
            }

            state.stats.lastVideoTime = now;
            state.stats.videoFrames++;

            // 画像表示
            const blob = new Blob([frameData], { type: 'image/jpeg' });
            const url = URL.createObjectURL(blob);

            // デバッグのため、データを16進数で一部表示
            const debugData = new Uint8Array(frameData);
            const header = Array.from(debugData.slice(0, 16))
                .map(b => b.toString(16).padStart(2, '0'))
                .join(' ');
            console.log(`フレームヘッダー: ${header}`);

            // JPEG署名をチェック (FF D8 はJPEGの先頭バイト)
            if (debugData[0] === 0xFF && debugData[1] === 0xD8) {
                console.log("有効なJPEG署名を検出");
            } else {
                console.warn("JPEG署名が見つかりません");
            }

            // onload/onerrorイベントハンドラを設定
            videoElem.onload = () => {
                console.log("画像ロード成功");
                URL.revokeObjectURL(url);
            };

            videoElem.onerror = (e) => {
                console.error("画像読み込みエラー:", e);
                URL.revokeObjectURL(url);
                logError('画像読み込みエラー');
            };

            // 画像をロード
            videoElem.src = url;
        } catch (e) {
            console.error('ビデオフレーム処理エラー:', e);
            logError(`フレーム処理エラー: ${e.message}`);
        }
    }

    // オーディオフレーム処理
    function processAudioFrame(frameData) {
        if (!state.audioEnabled) return;

        try {
            // フレームカウント更新
            state.stats.lastAudioTime = Date.now();
            state.stats.audioFrames++;

            // 16ビットPCMデータとして解釈
            const int16Array = new Int16Array(frameData);

            // バッファに追加
            state.audioBuffer.push(int16Array);

            // バッファサイズ制限
            while (state.audioBuffer.length > 5) {
                state.audioBuffer.shift();
            }

            // オーディオ処理の状態確認
            ensureAudioPlaying();
        } catch (e) {
            logError('オーディオフレーム処理エラー:', e);
        }
    }

    // WebSocketエラー発生時の処理
    function handleError(event) {
        logError('WebSocketエラー:', event);
        updateStatus('接続エラーが発生しました', true);
        state.stats.errors++;
    }

    // WebSocket切断時の処理
    function handleClose(event) {
        log(`接続が切断されました (コード: ${event.code}): ${event.reason || '理由なし'}`);
        updateStatus(`接続が切断されました (${event.code}) - 再接続中...`, true);

        // タイマーのクリア
        cleanup();

        // 再接続
        reconnect();
    }

    // 再接続処理
    function reconnect() {
        // 既存のタイマーをクリア
        clearTimeout(state.wsReconnectTimer);

        // 最大試行回数を超えた場合
        if (state.reconnectAttempts >= config.maxReconnectAttempts) {
            updateStatus(`${config.maxReconnectAttempts}回の再接続試行に失敗しました。ページを更新してください。`, true);
            return;
        }

        // 再接続カウンタを増加
        state.reconnectAttempts++;

        // 指数バックオフで再接続間隔を計算（最大値あり）
        const delay = Math.min(
            config.maxReconnectInterval,
            config.reconnectInterval * Math.pow(config.reconnectDecay, state.reconnectAttempts - 1)
        );

        updateStatus(`${state.reconnectAttempts}回目の再接続を${Math.round(delay/1000)}秒後に試みます...`, true);

        // 一定時間後に再接続
        state.wsReconnectTimer = setTimeout(connect, delay);
    }

    // Pingタイマー開始
    function startPingTimer() {
        // 既存のタイマーをクリア
        clearInterval(state.wsPingTimer);
        clearTimeout(state.wsPongTimer);

        // 定期的にPingを送信
        state.wsPingTimer = setInterval(() => {
            if (!isConnected()) {
                clearInterval(state.wsPingTimer);
                return;
            }

            try {
                // Pingを送信
                state.ws.send('ping');
                log('ping送信');

                // Pong応答タイムアウト設定
                clearTimeout(state.wsPongTimer);
                state.wsPongTimer = setTimeout(() => {
                    logError('Pongタイムアウト - 接続が失われた可能性があります');
                    if (state.ws) {
                        updateStatus('サーバーからの応答がありません - 再接続します...', true);
                        state.ws.close();
                        reconnect();
                    }
                }, config.pongTimeout);
            } catch (e) {
                logError('Ping送信エラー:', e);
                reconnect();
            }
        }, config.pingInterval);
    }

    // Pong受信時の処理
    function handlePong() {
        // Pongタイマーをクリア
        clearTimeout(state.wsPongTimer);
        state.lastPongTime = Date.now();
    }

    // リソース解放
    function cleanup() {
        clearTimeout(state.wsConnectingTimer);
        clearTimeout(state.wsReconnectTimer);
        clearInterval(state.wsPingTimer);
        clearTimeout(state.wsPongTimer);

        // 既存WebSocketの終了
        if (state.ws) {
            // イベントハンドラを削除
            state.ws.removeEventListener('open', handleOpen);
            state.ws.removeEventListener('message', handleMessage);
            state.ws.removeEventListener('error', handleError);
            state.ws.removeEventListener('close', handleClose);

            // まだ開いている場合は閉じる
            if (state.ws.readyState === WebSocket.OPEN) {
                try {
                    state.ws.close();
                } catch (e) {
                    logError('WebSocket切断エラー:', e);
                }
            }

            state.ws = null;
        }
    }

    // 接続状態チェック
    function isConnected() {
        return state.ws && state.ws.readyState === WebSocket.OPEN;
    }

    // オーディオ初期化
    function initAudioIfNeeded() {
        if (state.audioContext) return;

        try {
            log('オーディオ初期化中...');

            // AudioContext作成
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (!AudioContextClass) {
                logError('このブラウザはWeb Audio APIに対応していません');
                return;
            }

            state.audioContext = new AudioContextClass();

            // ScriptProcessor作成（非推奨だがサポートが広い）
            const bufferSize = 4096;
            state.audioProcessor = state.audioContext.createScriptProcessor(
                bufferSize, 0, 1
            );

            // オーディオ処理コールバック
            state.audioProcessor.onaudioprocess = (e) => {
                const output = e.outputBuffer.getChannelData(0);

                if (state.audioEnabled && state.audioBuffer.length > 0) {
                    // バッファからデータを取得して再生
                    const pcmData = state.audioBuffer.shift();

                    // 16ビットPCM (-32768～32767) を Float32 (-1.0～1.0) に変換
                    for (let i = 0; i < output.length; i++) {
                        output[i] = i < pcmData.length ? (pcmData[i] / 32768.0) : 0;
                    }

                    // オーディオインジケーター更新
                    updateAudioIndicator(pcmData);
                } else {
                    // データがない場合は無音
                    for (let i = 0; i < output.length; i++) {
                        output[i] = 0;
                    }
                }
            };

            // 出力に接続
            state.audioProcessor.connect(state.audioContext.destination);

            // 状態が中断されていたら再開要求
            if (state.audioContext.state === 'suspended') {
                log('オーディオが一時停止状態です - ユーザー操作が必要です');

                // タッチイベントでオーディオを有効化（特にiOS向け）
                document.addEventListener('click', resumeAudio, { once: true });
                document.addEventListener('touchstart', resumeAudio, { once: true });
            }

            log('オーディオ初期化成功');
        } catch (e) {
            logError('オーディオ初期化エラー:', e);
        }
    }

    // オーディオ再生状態を確認
    function ensureAudioPlaying() {
        if (state.audioContext && state.audioContext.state === 'suspended') {
            resumeAudio();
        }
    }

    // オーディオ再生再開
    function resumeAudio() {
        if (!state.audioContext) return;

        state.audioContext.resume()
            .then(() => log('オーディオコンテキスト再開成功'))
            .catch(e => logError('オーディオコンテキスト再開エラー:', e));
    }

    // オーディオ切り替え
    function toggleAudio() {
        state.audioEnabled = !state.audioEnabled;

        if (audioToggleBtn) {
            audioToggleBtn.textContent = `音声: ${state.audioEnabled ? 'オン' : 'オフ'}`;
        }

        log(`オーディオ ${state.audioEnabled ? '有効' : '無効'}`);

        // オーディオが無効なら、バッファをクリア
        if (!state.audioEnabled) {
            state.audioBuffer = [];
        }
    }

    // 音声レベルインジケーター更新
    function updateAudioIndicator(pcmData) {
        if (!audioIndicator || !pcmData || pcmData.length === 0) return;

        // 音声レベルを計算
        let sum = 0;
        const sampleCount = Math.min(pcmData.length, 1000);

        for (let i = 0; i < sampleCount; i++) {
            sum += Math.abs(pcmData[i]);
        }

        const average = sum / sampleCount;
        const level = average / 10000; // 感度調整

        // レベルに応じてインジケーターを点灯
        if (level > 0.01) {
            audioIndicator.classList.add('audio-active');

            // 短時間後に消灯
            setTimeout(() => {
                audioIndicator.classList.remove('audio-active');
            }, 150);
        }
    }

    // イベントリスナー設定
    function setupEventListeners() {
        // 音声切り替えボタン
        if (audioToggleBtn) {
            audioToggleBtn.addEventListener('click', toggleAudio);
        }

        // ページ表示状態の変化を監視
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                // ページが表示された時に接続状態を確認
                if (!isConnected()) {
                    log('ページが表示されました - 接続を確認します');
                    connect();
                }
            }
        });

        // オーディオインタラクション（iOSなど向け）
        document.addEventListener('click', () => ensureAudioPlaying(), { once: true });
        document.addEventListener('touchstart', () => ensureAudioPlaying(), { once: true });
    }

    // バイナリフレームからビデオデータを処理する関数
    function handleVideoFrame(mediaData) {
        if (!videoElem) return;

        try {
            // フレームレート制限 (30fpsまで)
            const now = Date.now();
            if (now - state.stats.lastVideoTime < 33) {
                return;
            }

            state.stats.lastVideoTime = now;
            state.stats.videoFrames++;

            // 画像表示
            const blob = new Blob([mediaData], { type: 'image/jpeg' });
            const url = URL.createObjectURL(blob);

            videoElem.onload = () => URL.revokeObjectURL(url);
            videoElem.onerror = () => {
                URL.revokeObjectURL(url);
                logError('画像読み込みエラー');
            };

            videoElem.src = url;
        } catch (e) {
            logError('ビデオフレーム処理エラー:', e);
        }
    }

    // バイナリフレームから音声データを処理する関数
    function handleAudioFrame(mediaData) {
        if (!state.audioEnabled) return;

        try {
            // フレームカウント更新
            state.stats.lastAudioTime = Date.now();
            state.stats.audioFrames++;

            // 16ビットPCMデータとして解釈
            const int16Array = new Int16Array(mediaData);

            // バッファに追加
            state.audioBuffer.push(int16Array);

            // バッファサイズ制限
            while (state.audioBuffer.length > 5) {
                state.audioBuffer.shift();
            }

            // オーディオ処理の状態確認
            ensureAudioPlaying();
        } catch (e) {
            logError('オーディオフレーム処理エラー:', e);
        }
    }

    // 初期化と接続開始
    function init() {
        log('クライアント初期化');
        setupEventListeners();
        connect();
    }

    // アプリケーション開始
    init();
});