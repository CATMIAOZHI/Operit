/* METADATA
{
  name: time

  display_name: {
    zh: "时间"
    en: "Time"
  }
  description: {
    zh: "提供时间相关功能。实际上，激活本包的同时已经能够获取时间了。"
    en: "Provides time-related utilities. In practice, current time is already available once this package is enabled."
  }
  enabledByDefault: true
  category: "Utility"
  tools: [
    {
      name: get_time
      description: {
        zh: "获取当前时间，包括日期、今天星期几、时区和本年第几周。"
        en: "Get the current time, including the date, the day of the week, the time zone and the week of the year."
      }
      parameters: []
    },
    {
      name: format_time
      description: {
        zh: "格式化时间。提供日期、星期、24 小时和 12 小时等格式。"
        en: "Format time. Provides date, weekday, 24-hour and 12-hour formats."
      }
      parameters: []
    }
  ]
}*/
const timePackage = (function () {
    // getDay() works on every JS engine; toLocaleDateString's weekday option does not.
    const WEEKDAYS_ZH = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    const WEEKDAYS_EN = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
    const pad = (n) => n.toString().padStart(2, '0');
    function utcOffset(now) {
        const minutes = -now.getTimezoneOffset();
        const sign = minutes >= 0 ? '+' : '-';
        const abs = Math.abs(minutes);
        return `UTC${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
    }
    // ISO 8601 week number: weeks start on Monday, week 1 holds the year's first Thursday.
    function isoWeek(now) {
        const d = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
        const day = d.getUTCDay() || 7;
        d.setUTCDate(d.getUTCDate() + 4 - day);
        const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
        return Math.ceil(((d.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
    }
    async function get_time() {
        const now = new Date();
        const day = now.getDay();
        const date = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
        const clock = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
        return {
            summary: `${date} ${WEEKDAYS_ZH[day]} (${WEEKDAYS_EN[day]}) ${clock} ${utcOffset(now)}`,
            timestamp: now.getTime(),
            iso: now.toISOString(),
            local: `${date} ${clock}`,
            timezone: utcOffset(now),
            date: {
                year: now.getFullYear(),
                month: now.getMonth() + 1,
                day: now.getDate(),
                weekday: WEEKDAYS_ZH[day],
                weekday_en: WEEKDAYS_EN[day],
                // 1 = Monday ... 7 = Sunday (ISO 8601)
                weekday_number: day === 0 ? 7 : day,
                is_weekend: day === 0 || day === 6,
                iso_week: isoWeek(now)
            },
            time: {
                hours: now.getHours(),
                minutes: now.getMinutes(),
                seconds: now.getSeconds()
            }
        };
    }
    async function format_time() {
        const now = new Date();
        const hours = now.getHours();
        const minutes = now.getMinutes();
        const seconds = now.getSeconds();
        const time24h = `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
        const suffix = hours >= 12 ? 'PM' : 'AM';
        const hour12 = hours % 12 === 0 ? 12 : hours % 12;
        const time12h = `${pad(hour12)}:${pad(minutes)}:${pad(seconds)} ${suffix}`;
        return {
            iso: now.toISOString(),
            date: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`,
            weekday: WEEKDAYS_ZH[now.getDay()],
            weekday_en: WEEKDAYS_EN[now.getDay()],
            time24h,
            time12h,
            timezone: utcOffset(now)
        };
    }
    return {
        get_time,
        format_time
    };
})();
// 逐个导出
exports.get_time = timePackage.get_time;
exports.format_time = timePackage.format_time;
